import os
import logging
import sqlite3
from datetime import datetime, timedelta
from typing import List, Tuple, Optional, Dict, Any
from threading import Thread
from flask import Flask
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import (
    Updater, CommandHandler, CallbackQueryHandler, MessageHandler,
    Filters, CallbackContext
)
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Configuration
BOT_TOKEN = os.getenv('BOT_TOKEN')
ADMIN_ID = int(os.getenv('ADMIN_ID', 0))
PORT = int(os.getenv('PORT', 5000))
DATABASE_PATH = '/tmp/bot_database.db'

# Configure logging
logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO
)
logger = logging.getLogger(__name__)

# Flask app for keeping the bot alive on Render
app = Flask(__name__)

@app.route('/')
def home():
    return "Bot is running!"

@app.route('/health')
def health():
    return "OK"

def run_flask():
    app.run(host='0.0.0.0', port=PORT)

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
            ('key_message', '🎉 Congratulations! Your key has been assigned:\\n\\n🔑 Key: {key}\\n⏰ Duration: {duration} days\\n📦 Product: {product}\\n🔗 Link: {link}')
        ''')
        
        conn.commit()
        conn.close()
        logger.info("Database initialized successfully")

    def execute_query(self, query: str, params: tuple = ()):
        """Execute a query"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute(query, params)
        conn.commit()
        result = cursor.fetchall()
        conn.close()
        return result

    def fetch_one(self, query: str, params: tuple = ()) -> Optional[Tuple]:
        """Fetch a single row"""
        result = self.execute_query(query, params)
        return result[0] if result else None

    def fetch_all(self, query: str, params: tuple = ()) -> List[Tuple]:
        """Fetch all rows"""
        return self.execute_query(query, params)

# Initialize database
db = DatabaseManager(DATABASE_PATH)

# Utility functions
def is_user_verified(user_id: int) -> bool:
    """Check if user is verified"""
    result = db.fetch_one("SELECT verified FROM users WHERE user_id = ?", (user_id,))
    return result[0] if result else False

def get_user_data(user_id: int) -> Dict[str, Any]:
    """Get user data"""
    result = db.fetch_one(
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

def update_user(user_id: int, username: str = None):
    """Update or create user"""
    user = get_user_data(user_id)
    if user:
        if username and username != user['username']:
            db.execute_query(
                "UPDATE users SET username = ? WHERE user_id = ?", 
                (username, user_id)
            )
    else:
        db.execute_query(
            "INSERT INTO users (user_id, username, verified) VALUES (?, ?, FALSE)",
            (user_id, username)
        )

def get_verification_channels() -> List[str]:
    """Get list of channels for verification"""
    results = db.fetch_all("SELECT username FROM channels")
    return [row[0] for row in results]

def check_channel_membership(bot, user_id: int, channel_username: str) -> bool:
    """Check if user is member of a channel"""
    try:
        # Remove @ if present
        if channel_username.startswith('@'):
            channel_username = channel_username[1:]
            
        chat_member = bot.get_chat_member(chat_id=f"@{channel_username}", user_id=user_id)
        return chat_member.status in ['member', 'administrator', 'creator']
    except Exception as e:
        logger.error(f"Error checking membership for {channel_username}: {e}")
        return False

def verify_all_channels(bot, user_id: int) -> bool:
    """Verify user membership in all required channels"""
    channels = get_verification_channels()
    if not channels:
        return True  # No channels to verify
    
    for channel in channels:
        if not check_channel_membership(bot, user_id, channel):
            return False
    return True

def get_available_key():
    """Get an available key (FIFO)"""
    result = db.fetch_one(
        "SELECT id, key_text, duration_days, meta_name, meta_link FROM keys WHERE used = FALSE ORDER BY added_at ASC LIMIT 1"
    )
    return result

def assign_key_to_user(user_id: int, username: str, key_data: Tuple) -> Dict[str, Any]:
    """Assign key to user and mark as used"""
    key_id, key_text, duration_days, meta_name, meta_link = key_data
    assigned_at = datetime.now()
    expires_at = assigned_at + timedelta(days=duration_days)
    
    # Mark key as used
    db.execute_query("UPDATE keys SET used = TRUE WHERE id = ?", (key_id,))
    
    # Update user data
    db.execute_query(
        "UPDATE users SET verified = TRUE, last_key_time = ?, total_keys_claimed = total_keys_claimed + 1 WHERE user_id = ?",
        (assigned_at, user_id)
    )
    
    # Record sale
    db.execute_query('''
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

def get_cooldown_hours() -> int:
    """Get cooldown period in hours"""
    result = db.fetch_one("SELECT value FROM settings WHERE key = 'cooldown_hours'")
    return int(result[0]) if result else 24

def get_key_message() -> str:
    """Get key assignment message template"""
    result = db.fetch_one("SELECT value FROM settings WHERE key = 'key_message'")
    return result[0] if result else "🎉 Your key: {key}"

def can_claim_key(user_id: int) -> Tuple[bool, Optional[str]]:
    """Check if user can claim a key"""
    user = get_user_data(user_id)
    if not user or not user['verified']:
        return False, "You need to verify your channel membership first!"
    
    if user['last_key_time']:
        cooldown_hours = get_cooldown_hours()
        last_claim = datetime.fromisoformat(user['last_key_time'])
        next_claim = last_claim + timedelta(hours=cooldown_hours)
        
        if datetime.now() < next_claim:
            time_left = next_claim - datetime.now()
            hours = int(time_left.total_seconds() // 3600)
            minutes = int((time_left.total_seconds() % 3600) // 60)
            return False, f"Cooldown active! Please wait {hours}h {minutes}m"
    
    # Check if keys are available
    available_key = get_available_key()
    if not available_key:
        return False, "Sorry, no keys available at the moment!"
    
    return True, None

def get_bot_stats() -> Dict[str, Any]:
    """Get comprehensive bot statistics"""
    total_users = db.fetch_one("SELECT COUNT(*) FROM users")
    verified_users = db.fetch_one("SELECT COUNT(*) FROM users WHERE verified = TRUE")
    total_keys = db.fetch_one("SELECT COUNT(*) FROM keys")
    used_keys = db.fetch_one("SELECT COUNT(*) FROM keys WHERE used = TRUE")
    available_keys = db.fetch_one("SELECT COUNT(*) FROM keys WHERE used = FALSE")
    total_sales = db.fetch_one("SELECT COUNT(*) FROM sales")
    
    recent_claims = db.fetch_all('''
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
    keyboard = [
        [
            InlineKeyboardButton("✅ Verify Membership", callback_data="verify"),
            InlineKeyboardButton("🎁 Claim Key", callback_data="start_claim")
        ]
    ]
    return InlineKeyboardMarkup(keyboard)

def get_admin_keyboard() -> InlineKeyboardMarkup:
    """Get admin panel keyboard"""
    keyboard = [
        [InlineKeyboardButton("📊 Statistics", callback_data="admin_stats")],
        [InlineKeyboardButton("🔑 Add Keys", callback_data="admin_add_keys")],
        [InlineKeyboardButton("📢 Add Channel", callback_data="admin_add_channel")],
        [InlineKeyboardButton("🗑 Remove Channel", callback_data="admin_remove_channel")],
        [InlineKeyboardButton("📋 List Channels", callback_data="admin_list_channels")],
        [InlineKeyboardButton("⏰ Set Cooldown", callback_data="admin_set_cooldown")],
        [InlineKeyboardButton("💬 Set Key Message", callback_data="admin_set_key_msg")],
        [InlineKeyboardButton("❌ Delete All Keys", callback_data="admin_delete_all_keys")],
        [InlineKeyboardButton("👥 User History", callback_data="admin_user_history")]
    ]
    return InlineKeyboardMarkup(keyboard)

def get_back_admin_keyboard() -> InlineKeyboardMarkup:
    """Get back to admin panel keyboard"""
    keyboard = [[InlineKeyboardButton("🔙 Back to Admin", callback_data="admin_back_main")]]
    return InlineKeyboardMarkup(keyboard)

def get_confirm_delete_keyboard() -> InlineKeyboardMarkup:
    """Get confirmation keyboard for delete operation"""
    keyboard = [
        [
            InlineKeyboardButton("✅ Confirm Delete", callback_data="confirm_delete_all_keys"),
            InlineKeyboardButton("❌ Cancel", callback_data="cancel_delete")
        ]
    ]
    return InlineKeyboardMarkup(keyboard)

# User handlers
def start(update: Update, context: CallbackContext) -> None:
    """Handle /start command"""
    user_id = update.effective_user.id
    username = update.effective_user.username
    
    update_user(user_id, username)
    
    channels = get_verification_channels()
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
    update.message.reply_text(welcome_text, reply_markup=keyboard)

def verify_callback(update: Update, context: CallbackContext) -> None:
    """Handle verification check"""
    query = update.callback_query
    query.answer()
    
    user_id = query.from_user.id
    username = query.from_user.username
    
    update_user(user_id, username)
    
    channels = get_verification_channels()
    if not channels:
        query.edit_message_text(
            "✅ No verification channels required. You're automatically verified!",
            reply_markup=get_main_keyboard()
        )
        return
    
    # Check membership in all channels
    is_member = verify_all_channels(context.bot, user_id)
    
    if is_member:
        db.execute_query("UPDATE users SET verified = TRUE WHERE user_id = ?", (user_id,))
        query.edit_message_text(
            "✅ Verification successful! You've joined all required channels.\n\nYou can now claim your key!",
            reply_markup=get_main_keyboard()
        )
    else:
        channel_links = "\n".join([f"• @{channel}" for channel in channels])
        query.edit_message_text(
            f"❌ Please join all required channels:\n\n{channel_links}\n\n"
            "After joining, click Verify Membership again.",
            reply_markup=get_main_keyboard()
        )

def claim_callback(update: Update, context: CallbackContext) -> None:
    """Handle key claim process"""
    query = update.callback_query
    query.answer()
    
    user_id = query.from_user.id
    username = query.from_user.username
    
    update_user(user_id, username)
    
    can_claim, reason = can_claim_key(user_id)
    
    if not can_claim:
        query.answer(reason, show_alert=True)
        return
    
    # Assign key
    key_data = get_available_key()
    if not key_data:
        query.answer("No keys available!", show_alert=True)
        return
    
    assigned_key = assign_key_to_user(user_id, username, key_data)
    
    # Get and format key message
    key_message_template = get_key_message()
    key_message = key_message_template.format(
        key=assigned_key['key'],
        duration=assigned_key['duration'],
        product=assigned_key['product'],
        link=assigned_key['link']
    )
    
    query.edit_message_text(
        key_message + f"\n\n⏰ Expires: {assigned_key['expires_at'].strftime('%Y-%m-%d %H:%M')}",
        reply_markup=get_main_keyboard()
    )

# Admin handlers
def admin_command(update: Update, context: CallbackContext) -> None:
    """Handle /admin command"""
    if update.effective_user.id != ADMIN_ID:
        update.message.reply_text("❌ Access denied.")
        return
    
    admin_text = "👨‍💼 Admin Panel\n\nSelect an option below:"
    update.message.reply_text(admin_text, reply_markup=get_admin_keyboard())

def admin_back_main_callback(update: Update, context: CallbackContext) -> None:
    """Return to main admin panel"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    admin_text = "👨‍💼 Admin Panel\n\nSelect an option below:"
    query.edit_message_text(admin_text, reply_markup=get_admin_keyboard())

def admin_stats_callback(update: Update, context: CallbackContext) -> None:
    """Show bot statistics"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    stats = get_bot_stats()
    
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
    
    query.edit_message_text(stats_text, reply_markup=get_back_admin_keyboard())

def admin_add_keys_callback(update: Update, context: CallbackContext) -> None:
    """Show add keys interface"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
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
    
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def process_admin_text(update: Update, context: CallbackContext) -> None:
    """Process admin text commands"""
    if update.effective_user.id != ADMIN_ID:
        return
    
    text = update.message.text.strip()
    
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
                db.execute_query('''
                    INSERT INTO keys (key_text, duration_days, meta_name, meta_link)
                    VALUES (?, ?, ?, ?)
                ''', (key_text, duration_days, meta_name, meta_link))
                keys_added += 1
            except sqlite3.IntegrityError:
                keys_duplicate += 1
        
        result_text = f"✅ Added {keys_added} keys"
        if keys_duplicate > 0:
            result_text += f"\n❌ {keys_duplicate} duplicate keys skipped"
        
        update.message.reply_text(result_text, reply_markup=get_back_admin_keyboard())
        return
    
    # Process channel addition/removal
    channels = get_verification_channels()
    if text and not text.startswith('/') and '|' not in text:
        # Remove @ if present
        if text.startswith('@'):
            channel_username = text[1:]
        else:
            channel_username = text
            
        # Check if channel exists
        if channel_username in channels:
            # Remove channel
            db.execute_query("DELETE FROM channels WHERE username = ?", (channel_username,))
            update.message.reply_text(f"✅ Channel @{channel_username} removed successfully!", reply_markup=get_back_admin_keyboard())
        else:
            # Add channel
            try:
                db.execute_query("INSERT INTO channels (username) VALUES (?)", (channel_username,))
                update.message.reply_text(f"✅ Channel @{channel_username} added successfully!", reply_markup=get_back_admin_keyboard())
            except sqlite3.IntegrityError:
                update.message.reply_text(f"❌ Channel @{channel_username} already exists!", reply_markup=get_back_admin_keyboard())
        return
    
    # Process cooldown setting
    if text.isdigit() and 1 <= int(text) <= 720:
        db.execute_query("UPDATE settings SET value = ? WHERE key = 'cooldown_hours'", (text,))
        update.message.reply_text(f"✅ Cooldown set to {text} hours!", reply_markup=get_back_admin_keyboard())
        return
    
    # Process key message setting
    if text and '{key}' in text and not text.startswith('/') and '|' not in text:
        db.execute_query("UPDATE settings SET value = ? WHERE key = 'key_message'", (text,))
        update.message.reply_text("✅ Key message updated successfully!", reply_markup=get_back_admin_keyboard())
        return

# Other admin callback handlers
def admin_add_channel_callback(update: Update, context: CallbackContext) -> None:
    """Show add channel interface"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
📢 Add Verification Channel

Send the channel username (without @):

Example: mychannel
"""
    
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_remove_channel_callback(update: Update, context: CallbackContext) -> None:
    """Show remove channel interface"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    channels = get_verification_channels()
    if not channels:
        query.edit_message_text("No channels to remove.", reply_markup=get_back_admin_keyboard())
        return
    
    channels_text = "\n".join([f"• @{channel}" for channel in channels])
    instructions = f"""
🗑 Remove Verification Channel

Current channels:
{channels_text}

Send the channel username to remove (without @):
"""
    
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_list_channels_callback(update: Update, context: CallbackContext) -> None:
    """List all verification channels"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    channels = get_verification_channels()
    if not channels:
        channels_text = "No channels configured."
    else:
        channels_text = "Current verification channels:\n\n" + "\n".join([f"• @{channel}" for channel in channels])
    
    query.edit_message_text(channels_text, reply_markup=get_back_admin_keyboard())

def admin_set_cooldown_callback(update: Update, context: CallbackContext) -> None:
    """Set cooldown period"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    current_cooldown = get_cooldown_hours()
    instructions = f"""
⏰ Set Cooldown Period

Current cooldown: {current_cooldown} hours

Send the new cooldown period in hours (number only):

Example: 24
"""
    
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_set_key_msg_callback(update: Update, context: CallbackContext) -> None:
    """Set key assignment message"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    current_msg = get_key_message()
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
    
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_delete_all_keys_callback(update: Update, context: CallbackContext) -> None:
    """Confirm delete all keys"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    stats = get_bot_stats()
    warning_text = f"""
❌ Delete All Keys - CONFIRMATION REQUIRED

This will permanently delete:
• {stats['available_keys']} available keys
• {stats['used_keys']} used keys (sales records will remain)

This action cannot be undone!

Are you sure you want to delete ALL keys?
"""
    
    query.edit_message_text(warning_text, reply_markup=get_confirm_delete_keyboard())

def confirm_delete_all_keys_callback(update: Update, context: CallbackContext) -> None:
    """Confirm and delete all keys"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    db.execute_query("DELETE FROM keys")
    query.edit_message_text("✅ All keys have been deleted successfully!", reply_markup=get_back_admin_keyboard())

def cancel_delete_callback(update: Update, context: CallbackContext) -> None:
    """Cancel deletion"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    query.edit_message_text("❌ Deletion cancelled.", reply_markup=get_back_admin_keyboard())

def admin_user_history_callback(update: Update, context: CallbackContext) -> None:
    """Show user claim history interface"""
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    # Get recent claims
    recent_claims = db.fetch_all('''
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
    
    query.edit_message_text(history_text, reply_markup=get_back_admin_keyboard())

def main() -> None:
    """Start the bot."""
    # Create the Updater
    updater = Updater(BOT_TOKEN, use_context=True)
    dispatcher = updater.dispatcher

    # Add handlers
    dispatcher.add_handler(CommandHandler("start", start))
    dispatcher.add_handler(CommandHandler("admin", admin_command))
    
    # Callback query handlers
    dispatcher.add_handler(CallbackQueryHandler(verify_callback, pattern="^verify$"))
    dispatcher.add_handler(CallbackQueryHandler(claim_callback, pattern="^start_claim$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_back_main_callback, pattern="^admin_back_main$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_stats_callback, pattern="^admin_stats$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_add_keys_callback, pattern="^admin_add_keys$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_add_channel_callback, pattern="^admin_add_channel$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_remove_channel_callback, pattern="^admin_remove_channel$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_list_channels_callback, pattern="^admin_list_channels$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_set_cooldown_callback, pattern="^admin_set_cooldown$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_set_key_msg_callback, pattern="^admin_set_key_msg$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_delete_all_keys_callback, pattern="^admin_delete_all_keys$"))
    dispatcher.add_handler(CallbackQueryHandler(confirm_delete_all_keys_callback, pattern="^confirm_delete_all_keys$"))
    dispatcher.add_handler(CallbackQueryHandler(cancel_delete_callback, pattern="^cancel_delete$"))
    dispatcher.add_handler(CallbackQueryHandler(admin_user_history_callback, pattern="^admin_user_history$"))
    
    # Admin text message handler
    dispatcher.add_handler(MessageHandler(Filters.text & Filters.user(ADMIN_ID), process_admin_text))

    # Start Flask server in a separate thread
    flask_thread = Thread(target=run_flask)
    flask_thread.daemon = True
    flask_thread.start()

    # Start the Bot
    logger.info("Bot started!")
    updater.start_polling()
    updater.idle()

if __name__ == "__main__":
    main()
