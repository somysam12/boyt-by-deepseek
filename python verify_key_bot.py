import asyncio
import logging
import os
import sqlite3
from datetime import datetime, timedelta
from typing import List, Tuple, Optional, Dict, Any

from aiogram import Bot, Dispatcher, types
from aiogram.types import InlineKeyboardMarkup, InlineKeyboardButton, CallbackQuery
from aiogram.utils.markdown import text, bold, code, pre
from aiogram.dispatcher import FSMContext
from aiogram.contrib.fsm_storage.memory import MemoryStorage
from aiogram.utils import executor
from dotenv import load_dotenv
import aiohttp
from aiohttp import web

# Load environment variables
load_dotenv()

# Configuration
BOT_TOKEN = os.getenv('BOT_TOKEN')
ADMIN_ID = int(os.getenv('ADMIN_ID', 0))
PORT = int(os.getenv('PORT', 10000))
DATABASE_PATH = '/tmp/bot_database.db'

# Initialize bot and dispatcher with memory storage
storage = MemoryStorage()
bot = Bot(token=BOT_TOKEN)
dp = Dispatcher(bot, storage=storage)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class DatabaseManager:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self.init_database()

    def init_database(self):
        """Initialize database with required tables"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        # Create tables
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS users (
                user_id INTEGER PRIMARY KEY,
                username TEXT,
                verified BOOLEAN DEFAULT FALSE,
                last_key_time TIMESTAMP,
                total_keys_claimed INTEGER DEFAULT 0,
                first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS keys (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key_text TEXT UNIQUE NOT NULL,
                duration_days INTEGER DEFAULT 30,
                meta_name TEXT DEFAULT 'Premium',
                meta_link TEXT DEFAULT '',
                used BOOLEAN DEFAULT FALSE,
                added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS sales (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                username TEXT,
                key_id INTEGER NOT NULL,
                key_text TEXT NOT NULL,
                assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP,
                active BOOLEAN DEFAULT TRUE,
                message_chat_id INTEGER,
                message_id INTEGER,
                FOREIGN KEY (key_id) REFERENCES keys (id)
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        ''')
        
        # Initialize default settings
        cursor.execute('''
            INSERT OR IGNORE INTO settings (key, value) VALUES 
            ('cooldown_hours', '24'),
            ('key_message', '🎉 Congratulations! Your key has been assigned:\n\n🔑 Key: {key}\n⏰ Duration: {duration} days\n📦 Product: {product}\n🔗 Link: {link}')
        ''')
        
        conn.commit()
        conn.close()
        logger.info("Database initialized successfully")

    async def execute_query(self, query: str, params: tuple = ()) -> sqlite3.Cursor:
        """Execute a query asynchronously"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute(query, params)
        conn.commit()
        return cursor

    async def fetch_one(self, query: str, params: tuple = ()) -> Optional[Tuple]:
        """Fetch a single row"""
        cursor = await self.execute_query(query, params)
        result = cursor.fetchone()
        cursor.connection.close()
        return result

    async def fetch_all(self, query: str, params: tuple = ()) -> List[Tuple]:
        """Fetch all rows"""
        cursor = await self.execute_query(query, params)
        result = cursor.fetchall()
        cursor.connection.close()
        return result

# Initialize database
db = DatabaseManager(DATABASE_PATH)

# Utility functions
async def is_user_verified(user_id: int) -> bool:
    """Check if user is verified"""
    result = await db.fetch_one("SELECT verified FROM users WHERE user_id = ?", (user_id,))
    return result[0] if result else False

async def get_user_data(user_id: int) -> Dict[str, Any]:
    """Get user data"""
    result = await db.fetch_one(
        "SELECT user_id, username, verified, last_key_time, total_keys_claimed, first_seen FROM users WHERE user_id = ?", 
        (user_id,)
    )
    if result:
        return {
            'user_id': result[0],
            'username': result[1],
            'verified': bool(result[2]),
            'last_key_time': result[3],
            'total_keys_claimed': result[4],
            'first_seen': result[5]
        }
    return {}

async def update_user(user_id: int, username: str = None):
    """Update or create user"""
    user = await get_user_data(user_id)
    if user:
        if username and username != user['username']:
            await db.execute_query(
                "UPDATE users SET username = ? WHERE user_id = ?", 
                (username, user_id)
            )
    else:
        await db.execute_query(
            "INSERT INTO users (user_id, username, verified) VALUES (?, ?, FALSE)",
            (user_id, username)
        )

async def get_verification_channels() -> List[str]:
    """Get list of channels for verification"""
    results = await db.fetch_all("SELECT username FROM channels")
    return [row[0] for row in results]

async def check_channel_membership(user_id: int, channel_username: str) -> bool:
    """Check if user is member of a channel"""
    try:
        # Remove @ if present
        if channel_username.startswith('@'):
            channel_username = channel_username[1:]
            
        chat_member = await bot.get_chat_member(f"@{channel_username}", user_id)
        return chat_member.status in ['member', 'administrator', 'creator']
    except Exception as e:
        logger.error(f"Error checking membership for {channel_username}: {e}")
        return False

async def verify_all_channels(user_id: int) -> bool:
    """Verify user membership in all required channels"""
    channels = await get_verification_channels()
    if not channels:
        return True  # No channels to verify
    
    for channel in channels:
        if not await check_channel_membership(user_id, channel):
            return False
    return True

async def get_available_key():
    """Get an available key (FIFO)"""
    result = await db.fetch_one(
        "SELECT id, key_text, duration_days, meta_name, meta_link FROM keys WHERE used = FALSE ORDER BY added_at ASC LIMIT 1"
    )
    return result

async def assign_key_to_user(user_id: int, username: str, key_data: Tuple) -> Dict[str, Any]:
    """Assign key to user and mark as used"""
    key_id, key_text, duration_days, meta_name, meta_link = key_data
    assigned_at = datetime.now()
    expires_at = assigned_at + timedelta(days=duration_days)
    
    # Mark key as used
    await db.execute_query("UPDATE keys SET used = TRUE WHERE id = ?", (key_id,))
    
    # Update user data
    await db.execute_query(
        "UPDATE users SET verified = TRUE, last_key_time = ?, total_keys_claimed = total_keys_claimed + 1 WHERE user_id = ?",
        (assigned_at, user_id)
    )
    
    # Record sale
    await db.execute_query('''
        INSERT INTO sales (user_id, username, key_id, key_text, assigned_at, expires_at, active)
        VALUES (?, ?, ?, ?, ?, ?, TRUE)
    ''', (user_id, username, key_id, key_text, assigned_at, expires_at))
    
    return {
        'key': key_text,
        'duration': duration_days,
        'product': meta_name,
        'link': meta_link,
        'expires_at': expires_at
    }

async def get_cooldown_hours() -> int:
    """Get cooldown period in hours"""
    result = await db.fetch_one("SELECT value FROM settings WHERE key = 'cooldown_hours'")
    return int(result[0]) if result else 24

async def get_key_message() -> str:
    """Get key assignment message template"""
    result = await db.fetch_one("SELECT value FROM settings WHERE key = 'key_message'")
    return result[0] if result else "🎉 Your key: {key}"

async def can_claim_key(user_id: int) -> Tuple[bool, Optional[str]]:
    """Check if user can claim a key"""
    user = await get_user_data(user_id)
    if not user or not user['verified']:
        return False, "You need to verify your channel membership first!"
    
    if user['last_key_time']:
        cooldown_hours = await get_cooldown_hours()
        last_claim = datetime.fromisoformat(user['last_key_time'])
        next_claim = last_claim + timedelta(hours=cooldown_hours)
        
        if datetime.now() < next_claim:
            time_left = next_claim - datetime.now()
            hours = int(time_left.total_seconds() // 3600)
            minutes = int((time_left.total_seconds() % 3600) // 60)
            return False, f"Cooldown active! Please wait {hours}h {minutes}m"
    
    # Check if keys are available
    available_key = await get_available_key()
    if not available_key:
        return False, "Sorry, no keys available at the moment!"
    
    return True, None

async def get_bot_stats() -> Dict[str, Any]:
    """Get comprehensive bot statistics"""
    total_users = await db.fetch_one("SELECT COUNT(*) FROM users")
    verified_users = await db.fetch_one("SELECT COUNT(*) FROM users WHERE verified = TRUE")
    total_keys = await db.fetch_one("SELECT COUNT(*) FROM keys")
    used_keys = await db.fetch_one("SELECT COUNT(*) FROM keys WHERE used = TRUE")
    available_keys = await db.fetch_one("SELECT COUNT(*) FROM keys WHERE used = FALSE")
    total_sales = await db.fetch_one("SELECT COUNT(*) FROM sales")
    
    recent_claims = await db.fetch_all('''
        SELECT u.username, k.key_text, s.assigned_at 
        FROM sales s 
        JOIN users u ON s.user_id = u.user_id 
        JOIN keys k ON s.key_id = k.id 
        ORDER BY s.assigned_at DESC LIMIT 5
    ''')
    
    return {
        'total_users': total_users[0] if total_users else 0,
        'verified_users': verified_users[0] if verified_users else 0,
        'total_keys': total_keys[0] if total_keys else 0,
        'used_keys': used_keys[0] if used_keys else 0,
        'available_keys': available_keys[0] if available_keys else 0,
        'total_sales': total_sales[0] if total_sales else 0,
        'recent_claims': recent_claims
    }

# Keyboard builders
def get_main_keyboard() -> InlineKeyboardMarkup:
    """Get main menu keyboard"""
    keyboard = InlineKeyboardMarkup(row_width=2)
    keyboard.add(
        InlineKeyboardButton("✅ Verify Membership", callback_data="verify"),
        InlineKeyboardButton("🎁 Claim Key", callback_data="start_claim")
    )
    return keyboard

def get_admin_keyboard() -> InlineKeyboardMarkup:
    """Get admin panel keyboard"""
    keyboard = InlineKeyboardMarkup(row_width=2)
    keyboard.add(
        InlineKeyboardButton("📊 Statistics", callback_data="admin_stats"),
        InlineKeyboardButton("🔑 Add Keys", callback_data="admin_add_keys"),
        InlineKeyboardButton("📢 Add Channel", callback_data="admin_add_channel"),
        InlineKeyboardButton("🗑 Remove Channel", callback_data="admin_remove_channel"),
        InlineKeyboardButton("📋 List Channels", callback_data="admin_list_channels"),
        InlineKeyboardButton("⏰ Set Cooldown", callback_data="admin_set_cooldown"),
        InlineKeyboardButton("💬 Set Key Message", callback_data="admin_set_key_msg"),
        InlineKeyboardButton("❌ Delete All Keys", callback_data="admin_delete_all_keys"),
        InlineKeyboardButton("👥 User History", callback_data="admin_user_history")
    )
    return keyboard

def get_back_admin_keyboard() -> InlineKeyboardMarkup:
    """Get back to admin panel keyboard"""
    keyboard = InlineKeyboardMarkup()
    keyboard.add(InlineKeyboardButton("🔙 Back to Admin", callback_data="admin_back_main"))
    return keyboard

def get_confirm_delete_keyboard() -> InlineKeyboardMarkup:
    """Get confirmation keyboard for delete operation"""
    keyboard = InlineKeyboardMarkup()
    keyboard.add(
        InlineKeyboardButton("✅ Confirm Delete", callback_data="confirm_delete_all_keys"),
        InlineKeyboardButton("❌ Cancel", callback_data="cancel_delete")
    )
    return keyboard

# User handlers
@dp.message_handler(commands=['start'])
async def cmd_start(message: types.Message):
    """Handle /start command"""
    user_id = message.from_user.id
    username = message.from_user.username
    
    await update_user(user_id, username)
    
    channels = await get_verification_channels()
    welcome_text = "🤖 Welcome to the Key Distribution Bot!\n\n"
    welcome_text += "To get your key, follow these steps:\n"
    welcome_text += "1. Join all required channels below\n"
    welcome_text += "2. Click 'Verify Membership'\n"
    welcome_text += "3. Claim your key!\n\n"
    
    if channels:
        welcome_text += "Required Channels:\n"
        for i, channel in enumerate(channels, 1):
            welcome_text += f"{i}. @{channel}\n"
    
    keyboard = get_main_keyboard()
    await message.answer(welcome_text, reply_markup=keyboard)

@dp.callback_query_handler(lambda c: c.data == "verify")
async def process_verify(callback_query: types.CallbackQuery):
    """Handle verification check"""
    user_id = callback_query.from_user.id
    username = callback_query.from_user.username
    
    await update_user(user_id, username)
    
    channels = await get_verification_channels()
    if not channels:
        await callback_query.message.edit_text(
            "✅ No verification channels required. You're automatically verified!",
            reply_markup=get_main_keyboard()
        )
        return
    
    # Check membership in all channels
    is_member = await verify_all_channels(user_id)
    
    if is_member:
        await db.execute_query("UPDATE users SET verified = TRUE WHERE user_id = ?", (user_id,))
        await callback_query.message.edit_text(
            "✅ Verification successful! You've joined all required channels.\n\nYou can now claim your key!",
            reply_markup=get_main_keyboard()
        )
    else:
        channel_links = "\n".join([f"• @{channel}" for channel in channels])
        await callback_query.message.edit_text(
            f"❌ Please join all required channels:\n\n{channel_links}\n\n"
            "After joining, click Verify Membership again.",
            reply_markup=get_main_keyboard()
        )

@dp.callback_query_handler(lambda c: c.data == "start_claim")
async def process_claim(callback_query: types.CallbackQuery):
    """Handle key claim process"""
    user_id = callback_query.from_user.id
    username = callback_query.from_user.username
    
    await update_user(user_id, username)
    
    can_claim, reason = await can_claim_key(user_id)
    
    if not can_claim:
        await callback_query.answer(reason, show_alert=True)
        return
    
    # Assign key
    key_data = await get_available_key()
    if not key_data:
        await callback_query.answer("No keys available!", show_alert=True)
        return
    
    assigned_key = await assign_key_to_user(user_id, username, key_data)
    
    # Get and format key message
    key_message_template = await get_key_message()
    key_message = key_message_template.format(
        key=assigned_key['key'],
        duration=assigned_key['duration'],
        product=assigned_key['product'],
        link=assigned_key['link']
    )
    
    await callback_query.message.edit_text(
        key_message + f"\n\n⏰ Expires: {assigned_key['expires_at'].strftime('%Y-%m-%d %H:%M')}",
        reply_markup=get_main_keyboard()
    )

# Admin handlers
@dp.message_handler(commands=['admin'])
async def cmd_admin(message: types.Message):
    """Handle /admin command"""
    if message.from_user.id != ADMIN_ID:
        await message.answer("❌ Access denied.")
        return
    
    admin_text = "👨‍💼 Admin Panel\n\nSelect an option below:"
    await message.answer(admin_text, reply_markup=get_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_back_main")
async def admin_back_main(callback_query: types.CallbackQuery):
    """Return to main admin panel"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    admin_text = "👨‍💼 Admin Panel\n\nSelect an option below:"
    await callback_query.message.edit_text(admin_text, reply_markup=get_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_stats")
async def admin_stats(callback_query: types.CallbackQuery):
    """Show bot statistics"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    stats = await get_bot_stats()
    
    stats_text = f"""
📊 Bot Statistics

👥 Users:
• Total Users: {stats['total_users']}
• Verified Users: {stats['verified_users']}

🔑 Keys:
• Total Keys: {stats['total_keys']}
• Used Keys: {stats['used_keys']}
• Available Keys: {stats['available_keys']}

💰 Sales:
• Total Claims: {stats['total_sales']}

Recent Claims:
"""
    
    for claim in stats['recent_claims']:
        username, key_text, assigned_at = claim
        time_ago = datetime.now() - datetime.fromisoformat(assigned_at)
        hours_ago = int(time_ago.total_seconds() // 3600)
        stats_text += f"• {username}: {key_text} ({hours_ago}h ago)\n"
    
    await callback_query.message.edit_text(stats_text, reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_add_keys")
async def admin_add_keys(callback_query: types.CallbackQuery):
    """Show add keys interface"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
🔑 Add Keys

Send keys in the following format (one per line):
key | duration_days | product_name | product_link

Example:
ABCD-EFGH-IJKL | 30 | Premium | https://example.com
XYZ-123 | 7 | Basic | https://example.com/basic

You can send multiple keys at once.
"""
    
    await callback_query.message.edit_text(instructions, reply_markup=get_back_admin_keyboard())

@dp.message_handler(lambda message: message.from_user.id == ADMIN_ID)
async def process_admin_text(message: types.Message):
    """Process admin text commands"""
    text = message.text.strip()
    
    # Check if it's key addition
    if '|' in text and not text.startswith('/'):
        keys_added = 0
        keys_duplicate = 0
        
        for line in text.split('\n'):
            line = line.strip()
            if not line:
                continue
                
            parts = [part.strip() for part in line.split('|')]
            if len(parts) < 2:
                continue
                
            key_text = parts[0]
            duration_days = int(parts[1]) if len(parts) > 1 and parts[1].strip().isdigit() else 30
            meta_name = parts[2] if len(parts) > 2 else "Premium"
            meta_link = parts[3] if len(parts) > 3 else ""
            
            try:
                await db.execute_query('''
                    INSERT INTO keys (key_text, duration_days, meta_name, meta_link)
                    VALUES (?, ?, ?, ?)
                ''', (key_text, duration_days, meta_name, meta_link))
                keys_added += 1
            except sqlite3.IntegrityError:
                keys_duplicate += 1
        
        result_text = f"✅ Added {keys_added} keys"
        if keys_duplicate > 0:
            result_text += f"\n❌ {keys_duplicate} duplicate keys skipped"
        
        await message.answer(result_text, reply_markup=get_back_admin_keyboard())
        return
    
    # Process channel addition/removal
    channels = await get_verification_channels()
    if text and not text.startswith('/') and '|' not in text:
        # Remove @ if present
        if text.startswith('@'):
            channel_username = text[1:]
        else:
            channel_username = text
            
        # Check if channel exists
        if channel_username in channels:
            # Remove channel
            await db.execute_query("DELETE FROM channels WHERE username = ?", (channel_username,))
            await message.answer(f"✅ Channel @{channel_username} removed successfully!", reply_markup=get_back_admin_keyboard())
        else:
            # Add channel
            try:
                await db.execute_query("INSERT INTO channels (username) VALUES (?)", (channel_username,))
                await message.answer(f"✅ Channel @{channel_username} added successfully!", reply_markup=get_back_admin_keyboard())
            except sqlite3.IntegrityError:
                await message.answer(f"❌ Channel @{channel_username} already exists!", reply_markup=get_back_admin_keyboard())
        return
    
    # Process cooldown setting
    if text.isdigit() and 1 <= int(text) <= 720:  # Reasonable cooldown range
        await db.execute_query("UPDATE settings SET value = ? WHERE key = 'cooldown_hours'", (text,))
        await message.answer(f"✅ Cooldown set to {text} hours!", reply_markup=get_back_admin_keyboard())
        return
    
    # Process key message setting
    if text and '{key}' in text and not text.startswith('/') and '|' not in text:
        await db.execute_query("UPDATE settings SET value = ? WHERE key = 'key_message'", (text,))
        await message.answer("✅ Key message updated successfully!", reply_markup=get_back_admin_keyboard())
        return

@dp.callback_query_handler(lambda c: c.data == "admin_add_channel")
async def admin_add_channel(callback_query: types.CallbackQuery):
    """Show add channel interface"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
📢 Add Verification Channel

Send the channel username (without @):

Example: mychannel
"""
    
    await callback_query.message.edit_text(instructions, reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_remove_channel")
async def admin_remove_channel(callback_query: types.CallbackQuery):
    """Show remove channel interface"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    channels = await get_verification_channels()
    if not channels:
        await callback_query.message.edit_text("No channels to remove.", reply_markup=get_back_admin_keyboard())
        return
    
    channels_text = "\n".join([f"• @{channel}" for channel in channels])
    instructions = f"""
🗑 Remove Verification Channel

Current channels:
{channels_text}

Send the channel username to remove (without @):
"""
    
    await callback_query.message.edit_text(instructions, reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_list_channels")
async def admin_list_channels(callback_query: types.CallbackQuery):
    """List all verification channels"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    channels = await get_verification_channels()
    if not channels:
        channels_text = "No channels configured."
    else:
        channels_text = "Current verification channels:\n\n" + "\n".join([f"• @{channel}" for channel in channels])
    
    await callback_query.message.edit_text(channels_text, reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_set_cooldown")
async def admin_set_cooldown(callback_query: types.CallbackQuery):
    """Set cooldown period"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    current_cooldown = await get_cooldown_hours()
    instructions = f"""
⏰ Set Cooldown Period

Current cooldown: {current_cooldown} hours

Send the new cooldown period in hours (number only):

Example: 24
"""
    
    await callback_query.message.edit_text(instructions, reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_set_key_msg")
async def admin_set_key_msg(callback_query: types.CallbackQuery):
    """Set key assignment message"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    current_msg = await get_key_message()
    instructions = f"""
💬 Set Key Assignment Message

Current message:
{current_msg}

Available placeholders:
{{key}} - The assigned key
{{duration}} - Duration in days
{{product}} - Product name
{{link}} - Product link

Send the new message:
"""
    
    await callback_query.message.edit_text(instructions, reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_delete_all_keys")
async def admin_delete_all_keys(callback_query: types.CallbackQuery):
    """Confirm delete all keys"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    stats = await get_bot_stats()
    warning_text = f"""
❌ Delete All Keys - CONFIRMATION REQUIRED

This will permanently delete:
• {stats['available_keys']} available keys
• {stats['used_keys']} used keys (sales records will remain)

This action cannot be undone!

Are you sure you want to delete ALL keys?
"""
    
    await callback_query.message.edit_text(warning_text, reply_markup=get_confirm_delete_keyboard())

@dp.callback_query_handler(lambda c: c.data == "confirm_delete_all_keys")
async def confirm_delete_all_keys(callback_query: types.CallbackQuery):
    """Confirm and delete all keys"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    await db.execute_query("DELETE FROM keys")
    await callback_query.message.edit_text("✅ All keys have been deleted successfully!", reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "cancel_delete")
async def cancel_delete(callback_query: types.CallbackQuery):
    """Cancel deletion"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    await callback_query.message.edit_text("❌ Deletion cancelled.", reply_markup=get_back_admin_keyboard())

@dp.callback_query_handler(lambda c: c.data == "admin_user_history")
async def admin_user_history(callback_query: types.CallbackQuery):
    """Show user claim history interface"""
    if callback_query.from_user.id != ADMIN_ID:
        await callback_query.answer("Access denied", show_alert=True)
        return
    
    # Get recent claims
    recent_claims = await db.fetch_all('''
        SELECT u.user_id, u.username, k.key_text, s.assigned_at, k.meta_name
        FROM sales s 
        JOIN users u ON s.user_id = u.user_id 
        JOIN keys k ON s.key_id = k.id 
        ORDER BY s.assigned_at DESC LIMIT 10
    ''')
    
    if not recent_claims:
        history_text = "No claim history found."
    else:
        history_text = "👥 Recent User Claims (Last 10):\n\n"
        for claim in recent_claims:
            user_id, username, key_text, assigned_at, product = claim
            time_ago = datetime.now() - datetime.fromisoformat(assigned_at)
            hours_ago = int(time_ago.total_seconds() // 3600)
            history_text += f"👤 {username} ({user_id})\n"
            history_text += f"🔑 {key_text} | {product}\n"
            history_text += f"⏰ {hours_ago}h ago\n\n"
    
    await callback_query.message.edit_text(history_text, reply_markup=get_back_admin_keyboard())

# Web server for Render.com
async def handle_health_check(request):
    return web.Response(text="Bot is running!")

async def start_web_server():
    app = web.Application()
    app.router.add_get('/', handle_health_check)
    app.router.add_get('/health', handle_health_check)
    
    runner = web.AppRunner(app)
    await runner.setup()
    
    port = int(os.getenv('PORT', 10000))
    site = web.TCPSite(runner, '0.0.0.0', port)
    
    await site.start()
    logger.info(f"Web server started on port {port}")
    return runner

async def on_startup(dp):
    """Function to run on bot startup"""
    logger.info("Bot started!")
    await start_web_server()

async def on_shutdown(dp):
    """Function to run on bot shutdown"""
    logger.info("Bot shutting down...")
    await bot.close()

if __name__ == "__main__":
    # Start the bot
    executor.start_polling(dp, on_startup=on_startup, on_shutdown=on_shutdown, skip_updates=True)
