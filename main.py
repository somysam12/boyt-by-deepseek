import os
import logging
import sqlite3
import re
from datetime import datetime, timedelta
from typing import List, Tuple, Optional, Dict, Any
from threading import Thread
from flask import Flask
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup, InputFile
from telegram.ext import (
    Updater, CommandHandler, CallbackQueryHandler, MessageHandler,
    Filters, CallbackContext
)
from dotenv import load_dotenv

load_dotenv()

BOT_TOKEN = os.getenv('BOT_TOKEN')
if not BOT_TOKEN:
    raise ValueError("BOT_TOKEN environment variable is required")

ADMIN_ID_STR = os.getenv('ADMIN_ID')
if not ADMIN_ID_STR:
    raise ValueError("ADMIN_ID environment variable is required")
ADMIN_ID = int(ADMIN_ID_STR)
PORT = int(os.getenv('PORT', 5000))
DATABASE_PATH = '/tmp/bot_database.db'

logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO
)
logger = logging.getLogger(__name__)

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
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                channel_link TEXT DEFAULT ''
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS users (
                user_id INTEGER PRIMARY KEY,
                username TEXT,
                verified BOOLEAN DEFAULT FALSE,
                last_key_time TIMESTAMP,
                total_keys_claimed INTEGER DEFAULT 0,
                first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                blocked BOOLEAN DEFAULT FALSE,
                block_reason TEXT DEFAULT ''
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS keys (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key_text TEXT UNIQUE NOT NULL,
                duration_value INTEGER DEFAULT 30,
                duration_unit TEXT DEFAULT 'days',
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
                left_channel BOOLEAN DEFAULT FALSE,
                FOREIGN KEY (key_id) REFERENCES keys (id)
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS waitlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                username TEXT,
                added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                notified_admin BOOLEAN DEFAULT FALSE,
                FOREIGN KEY (user_id) REFERENCES users (user_id)
            )
        ''')
        
        cursor.execute('''
            INSERT OR IGNORE INTO settings (key, value) VALUES 
            ('cooldown_hours', '24'),
            ('key_message', '🎉 Congratulations! Your key has been assigned:\n\n🔑 Key: {key}\n⏰ Duration: {duration}\n📦 Product: {product}\n🔗 Link: {link}'),
            ('awaiting_admin_action', '')
        ''')
        
        conn.commit()
        conn.close()
        logger.info("Database initialized successfully")

    def execute_query(self, query: str, params: tuple = ()):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute(query, params)
        conn.commit()
        result = cursor.fetchall()
        conn.close()
        return result

    def fetch_one(self, query: str, params: tuple = ()) -> Optional[Tuple]:
        result = self.execute_query(query, params)
        return result[0] if result else None

    def fetch_all(self, query: str, params: tuple = ()) -> List[Tuple]:
        return self.execute_query(query, params)

db = DatabaseManager(DATABASE_PATH)

# User state management
user_states = {}

def set_user_state(user_id: int, state: str, data: Any = None):
    user_states[user_id] = {'state': state, 'data': data}

def get_user_state(user_id: int):
    return user_states.get(user_id, {'state': None, 'data': None})

def clear_user_state(user_id: int):
    if user_id in user_states:
        del user_states[user_id]

# Utility functions
def is_user_blocked(user_id: int) -> Tuple[bool, str]:
    result = db.fetch_one("SELECT blocked, block_reason FROM users WHERE user_id = ?", (user_id,))
    if result and result[0]:
        return True, result[1] or "You have been blocked by admin."
    return False, ""

def get_user_data(user_id: int) -> Dict[str, Any]:
    result = db.fetch_one(
        "SELECT user_id, username, verified, last_key_time, total_keys_claimed, first_seen, blocked, block_reason FROM users WHERE user_id = ?", 
        (user_id,)
    )
    if result:
        return {
            'user_id': result[0],
            'username': result[1],
            'verified': bool(result[2]),
            'last_key_time': result[3],
            'total_keys_claimed': result[4],
            'first_seen': result[5],
            'blocked': bool(result[6]),
            'block_reason': result[7]
        }
    return {}

def update_user(user_id: int, username: str = None):
    user = get_user_data(user_id)
    if user:
        if username and username != user['username']:
            db.execute_query("UPDATE users SET username = ? WHERE user_id = ?", (username, user_id))
    else:
        db.execute_query(
            "INSERT INTO users (user_id, username, verified, blocked) VALUES (?, ?, FALSE, FALSE)",
            (user_id, username)
        )

def get_verification_channels() -> List[Tuple[str, str]]:
    results = db.fetch_all("SELECT username, channel_link FROM channels")
    return [(row[0], row[1] or f"@{row[0]}") for row in results]

def check_channel_membership(bot, user_id: int, channel_username: str) -> bool:
    try:
        if channel_username.startswith('@'):
            channel_username = channel_username[1:]
        chat_member = bot.get_chat_member(chat_id=f"@{channel_username}", user_id=user_id)
        return chat_member.status in ['member', 'administrator', 'creator']
    except Exception as e:
        logger.error(f"Error checking membership for {channel_username}: {e}")
        return False

def verify_all_channels(bot, user_id: int) -> bool:
    channels = get_verification_channels()
    if not channels:
        return True
    for channel, _ in channels:
        if not check_channel_membership(bot, user_id, channel):
            return False
    return True

def check_users_left_channels(bot):
    """Check if users who claimed keys left channels"""
    active_sales = db.fetch_all('''
        SELECT DISTINCT s.user_id, s.id 
        FROM sales s 
        WHERE s.active = TRUE AND s.left_channel = FALSE
    ''')
    
    for user_id, sale_id in active_sales:
        if not verify_all_channels(bot, user_id):
            db.execute_query("UPDATE sales SET left_channel = TRUE WHERE id = ?", (sale_id,))

def get_available_key():
    result = db.fetch_one(
        "SELECT id, key_text, duration_value, duration_unit, meta_name, meta_link FROM keys WHERE used = FALSE ORDER BY added_at ASC LIMIT 1"
    )
    return result

def parse_duration(duration_str: str) -> Tuple[int, str]:
    """Parse duration string like '24h', '7d', '30days', '12hours'"""
    duration_str = duration_str.strip().lower()
    
    if duration_str.endswith('h') or 'hour' in duration_str:
        value = int(re.sub(r'[^\d]', '', duration_str))
        return value, 'hours'
    elif duration_str.endswith('d') or 'day' in duration_str:
        value = int(re.sub(r'[^\d]', '', duration_str))
        return value, 'days'
    else:
        return int(duration_str), 'days'

def get_duration_in_hours(duration_value: int, duration_unit: str) -> int:
    """Convert duration to hours"""
    if duration_unit == 'days':
        return duration_value * 24
    return duration_value

def format_duration(duration_value: int, duration_unit: str) -> str:
    """Format duration for display"""
    if duration_unit == 'hours':
        if duration_value >= 24:
            days = duration_value // 24
            hours = duration_value % 24
            if hours > 0:
                return f"{days} days {hours} hours"
            return f"{days} days"
        return f"{duration_value} hours"
    return f"{duration_value} days"

def assign_key_to_user(user_id: int, username: str, key_data: Tuple) -> Dict[str, Any]:
    key_id, key_text, duration_value, duration_unit, meta_name, meta_link = key_data
    assigned_at = datetime.now()
    
    hours = get_duration_in_hours(duration_value, duration_unit)
    expires_at = assigned_at + timedelta(hours=hours)
    
    db.execute_query("UPDATE keys SET used = TRUE WHERE id = ?", (key_id,))
    db.execute_query(
        "UPDATE users SET verified = TRUE, last_key_time = ?, total_keys_claimed = total_keys_claimed + 1 WHERE user_id = ?",
        (assigned_at, user_id)
    )
    db.execute_query('''
        INSERT INTO sales (user_id, username, key_id, key_text, assigned_at, expires_at, active)
        VALUES (?, ?, ?, ?, ?, ?, TRUE)
    ''', (user_id, username, key_id, key_text, assigned_at, expires_at))
    
    return {
        'key': key_text,
        'duration': format_duration(duration_value, duration_unit),
        'product': meta_name,
        'link': meta_link,
        'expires_at': expires_at
    }

def get_cooldown_hours() -> int:
    result = db.fetch_one("SELECT value FROM settings WHERE key = 'cooldown_hours'")
    return int(result[0]) if result else 24

def get_key_message() -> str:
    result = db.fetch_one("SELECT value FROM settings WHERE key = 'key_message'")
    return result[0] if result else "🎉 Your key: {key}"

def can_claim_key(user_id: int) -> Tuple[bool, Optional[str], Optional[int]]:
    user = get_user_data(user_id)
    if not user or not user['verified']:
        return False, "❌ You need to verify your channel membership first!", None
    
    if user['last_key_time']:
        cooldown_hours = get_cooldown_hours()
        last_claim = datetime.fromisoformat(user['last_key_time'])
        next_claim = last_claim + timedelta(hours=cooldown_hours)
        
        if datetime.now() < next_claim:
            time_left = next_claim - datetime.now()
            seconds_left = int(time_left.total_seconds())
            hours = seconds_left // 3600
            minutes = (seconds_left % 3600) // 60
            return False, f"⏳ Cooldown active!\n\n⏰ Time left: {hours}h {minutes}m", seconds_left
    
    available_key = get_available_key()
    if not available_key:
        return False, "😔 Sorry, no keys available at the moment!", None
    
    return True, None, None

def format_countdown(seconds: int) -> str:
    hours = seconds // 3600
    minutes = (seconds % 3600) // 60
    secs = seconds % 60
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"

# Waitlist functions
def add_to_waitlist(user_id: int, username: str = None) -> bool:
    """Add user to waitlist if not already in it. Returns True if user was added, False if already exists"""
    existing = db.fetch_one("SELECT id FROM waitlist WHERE user_id = ?", (user_id,))
    if not existing:
        db.execute_query(
            "INSERT INTO waitlist (user_id, username, notified_admin) VALUES (?, ?, FALSE)",
            (user_id, username)
        )
        return True
    return False

def remove_from_waitlist(user_id: int) -> None:
    """Remove user from waitlist"""
    db.execute_query("DELETE FROM waitlist WHERE user_id = ?", (user_id,))

def get_waitlist_users() -> List[Tuple]:
    """Get all users in waitlist"""
    return db.fetch_all("SELECT user_id, username, added_at FROM waitlist ORDER BY added_at ASC")

def notify_admin_waitlist(bot, user_id: int, username: str = None) -> None:
    """Notify admin when user is added to waitlist"""
    try:
        waitlist_count = db.fetch_one("SELECT COUNT(*) FROM waitlist")[0]
        message = f"⚠️ User Added to Waitlist!\n\n"
        message += f"👤 User ID: {user_id}\n"
        message += f"📝 Username: @{username or 'N/A'}\n"
        message += f"📋 Waitlist Size: {waitlist_count} user(s)\n\n"
        message += f"💡 Add keys using /admin → Add Keys"
        bot.send_message(chat_id=ADMIN_ID, text=message)
    except Exception as e:
        logger.error(f"Failed to notify admin about waitlist: {e}")

def process_waitlist(bot) -> None:
    """Process waitlist and assign keys to waiting users"""
    waitlist = get_waitlist_users()
    assigned_count = 0
    
    for user_id, username, added_at in waitlist:
        # Check if user is blocked
        blocked, reason = is_user_blocked(user_id)
        if blocked:
            remove_from_waitlist(user_id)
            continue
        
        # Check if user can still claim (verified, not in cooldown)
        user = get_user_data(user_id)
        if not user or not user['verified']:
            remove_from_waitlist(user_id)
            continue
        
        # Check cooldown
        if user['last_key_time']:
            cooldown_hours = get_cooldown_hours()
            last_claim = datetime.fromisoformat(user['last_key_time'])
            next_claim = last_claim + timedelta(hours=cooldown_hours)
            if datetime.now() < next_claim:
                continue  # Still in cooldown, skip
        
        # Try to assign key
        key_data = get_available_key()
        if not key_data:
            break  # No more keys available
        
        assigned_key = assign_key_to_user(user_id, username, key_data)
        
        # Send key to user
        try:
            key_message_template = get_key_message()
            key_message = key_message_template.format(
                key=assigned_key['key'],
                duration=assigned_key['duration'],
                product=assigned_key['product'],
                link=assigned_key['link']
            )
            bot.send_message(
                chat_id=user_id,
                text=f"🎉 Your key is ready!\n\n{key_message}\n\n⏰ Expires: {assigned_key['expires_at'].strftime('%Y-%m-%d %H:%M')}"
            )
            assigned_count += 1
        except Exception as e:
            logger.error(f"Failed to send key to user {user_id}: {e}")
        
        # Remove from waitlist
        remove_from_waitlist(user_id)
    
    # Notify admin if keys were assigned
    if assigned_count > 0:
        try:
            bot.send_message(
                chat_id=ADMIN_ID,
                text=f"✅ Assigned {assigned_count} key(s) to waitlist users!"
            )
        except Exception as e:
            logger.error(f"Failed to notify admin about waitlist assignment: {e}")

# Keyboard builders
def get_main_keyboard(bot=None, user_id=None) -> InlineKeyboardMarkup:
    keyboard = []
    
    channels = get_verification_channels()
    if channels and user_id:
        for channel_name, channel_link in channels:
            keyboard.append([InlineKeyboardButton(f"📢 Join @{channel_name}", url=f"https://t.me/{channel_name}")])
    
    keyboard.append([
        InlineKeyboardButton("✅ Verify Membership", callback_data="verify"),
        InlineKeyboardButton("🎁 Claim Key", callback_data="start_claim")
    ])
    
    return InlineKeyboardMarkup(keyboard)

def get_admin_keyboard() -> InlineKeyboardMarkup:
    keyboard = [
        [InlineKeyboardButton("📊 Statistics", callback_data="admin_stats"),
         InlineKeyboardButton("👥 All Users", callback_data="admin_all_users")],
        [InlineKeyboardButton("🔑 Add Keys", callback_data="admin_add_keys"),
         InlineKeyboardButton("⏳ Waitlist", callback_data="admin_waitlist")],
        [InlineKeyboardButton("📢 Add Channel", callback_data="admin_add_channel"),
         InlineKeyboardButton("🗑 Remove Channel", callback_data="admin_remove_channel")],
        [InlineKeyboardButton("📋 List Channels", callback_data="admin_list_channels")],
        [InlineKeyboardButton("⏰ Set Cooldown", callback_data="admin_set_cooldown"),
         InlineKeyboardButton("💬 Set Key Message", callback_data="admin_set_key_msg")],
        [InlineKeyboardButton("🚫 Block/Mute User", callback_data="admin_block_user")],
        [InlineKeyboardButton("📣 Send Announcement", callback_data="admin_announcement")],
        [InlineKeyboardButton("🚪 Users Who Left", callback_data="admin_left_users"),
         InlineKeyboardButton("❌ Delete All Keys", callback_data="admin_delete_all_keys")]
    ]
    return InlineKeyboardMarkup(keyboard)

def get_back_admin_keyboard() -> InlineKeyboardMarkup:
    keyboard = [[InlineKeyboardButton("🔙 Back to Admin", callback_data="admin_back_main")]]
    return InlineKeyboardMarkup(keyboard)

def get_announcement_type_keyboard() -> InlineKeyboardMarkup:
    keyboard = [
        [InlineKeyboardButton("📝 Text Only", callback_data="announce_text")],
        [InlineKeyboardButton("🖼 With Photo", callback_data="announce_photo")],
        [InlineKeyboardButton("🔙 Back", callback_data="admin_back_main")]
    ]
    return InlineKeyboardMarkup(keyboard)

# User handlers
def start(update: Update, context: CallbackContext) -> None:
    user_id = update.effective_user.id
    username = update.effective_user.username
    
    blocked, reason = is_user_blocked(user_id)
    if blocked:
        update.message.reply_text(f"🚫 {reason}")
        return
    
    update_user(user_id, username)
    
    welcome_text = "🎉 Welcome to the Key Distribution Bot!\n\n"
    welcome_text += "📝 To get your key, follow these steps:\n"
    welcome_text += "1️⃣ Join all required channels by clicking buttons below\n"
    welcome_text += "2️⃣ Click '✅ Verify Membership'\n"
    welcome_text += "3️⃣ Click '🎁 Claim Key' to get your key!\n"
    
    keyboard = get_main_keyboard(context.bot, user_id)
    update.message.reply_text(welcome_text, reply_markup=keyboard)

def verify_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    user_id = query.from_user.id
    username = query.from_user.username
    
    blocked, reason = is_user_blocked(user_id)
    if blocked:
        query.answer(f"🚫 {reason}", show_alert=True)
        return
    
    update_user(user_id, username)
    
    channels = get_verification_channels()
    if not channels:
        db.execute_query("UPDATE users SET verified = TRUE WHERE user_id = ?", (user_id,))
        query.edit_message_text(
            "✅ No verification channels required. You're automatically verified!\n\nYou can now claim your key!",
            reply_markup=get_main_keyboard(context.bot, user_id)
        )
        return
    
    is_member = verify_all_channels(context.bot, user_id)
    
    if is_member:
        db.execute_query("UPDATE users SET verified = TRUE WHERE user_id = ?", (user_id,))
        query.edit_message_text(
            "✅ Verification successful! You've joined all required channels.\n\n🎁 You can now claim your key!",
            reply_markup=get_main_keyboard(context.bot, user_id)
        )
    else:
        query.edit_message_text(
            "❌ Please join all required channels using the buttons above.\n\n"
            "After joining all channels, click '✅ Verify Membership' again.",
            reply_markup=get_main_keyboard(context.bot, user_id)
        )

def claim_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    user_id = query.from_user.id
    username = query.from_user.username
    
    blocked, reason = is_user_blocked(user_id)
    if blocked:
        query.answer(f"🚫 {reason}", show_alert=True)
        return
    
    update_user(user_id, username)
    
    # Check if user is verified
    user = get_user_data(user_id)
    if not user or not user['verified']:
        query.answer("❌ You need to verify your channel membership first!", show_alert=True)
        return
    
    # Check cooldown
    if user['last_key_time']:
        cooldown_hours = get_cooldown_hours()
        last_claim = datetime.fromisoformat(user['last_key_time'])
        next_claim = last_claim + timedelta(hours=cooldown_hours)
        
        if datetime.now() < next_claim:
            time_left = next_claim - datetime.now()
            seconds_left = int(time_left.total_seconds())
            hours = seconds_left // 3600
            minutes = (seconds_left % 3600) // 60
            query.answer(f"⏳ Cooldown active!\n\n⏰ Time left: {hours}h {minutes}m", show_alert=True)
            return
    
    # Check for available keys
    key_data = get_available_key()
    if not key_data:
        # Add to waitlist and notify admin only if newly added
        was_added = add_to_waitlist(user_id, username)
        if was_added:
            notify_admin_waitlist(context.bot, user_id, username)
            query.answer("😔 No keys available right now!\n\n✅ You've been added to the waitlist.\n📬 You'll receive your key automatically when admin adds new keys!", show_alert=True)
        else:
            query.answer("😔 No keys available right now!\n\n⏳ You're already on the waitlist.\n📬 You'll receive your key automatically when admin adds new keys!", show_alert=True)
        return
    
    assigned_key = assign_key_to_user(user_id, username, key_data)
    
    key_message_template = get_key_message()
    key_message = key_message_template.format(
        key=assigned_key['key'],
        duration=assigned_key['duration'],
        product=assigned_key['product'],
        link=assigned_key['link']
    )
    
    query.edit_message_text(
        key_message + f"\n\n⏰ Expires: {assigned_key['expires_at'].strftime('%Y-%m-%d %H:%M')}",
        reply_markup=get_main_keyboard(context.bot, user_id)
    )

# Admin handlers
def admin_command(update: Update, context: CallbackContext) -> None:
    if update.effective_user.id != ADMIN_ID:
        update.message.reply_text("❌ Access denied.")
        return
    
    admin_text = "👨‍💼 Admin Panel\n\nSelect an option below:"
    update.message.reply_text(admin_text, reply_markup=get_admin_keyboard())

def admin_back_main_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    clear_user_state(ADMIN_ID)
    admin_text = "👨‍💼 Admin Panel\n\nSelect an option below:"
    query.edit_message_text(admin_text, reply_markup=get_admin_keyboard())

def admin_stats_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    total_users_result = db.fetch_one("SELECT COUNT(*) FROM users")
    total_users = total_users_result[0] if total_users_result else 0
    verified_users_result = db.fetch_one("SELECT COUNT(*) FROM users WHERE verified = TRUE")
    verified_users = verified_users_result[0] if verified_users_result else 0
    total_keys_result = db.fetch_one("SELECT COUNT(*) FROM keys")
    total_keys = total_keys_result[0] if total_keys_result else 0
    used_keys_result = db.fetch_one("SELECT COUNT(*) FROM keys WHERE used = TRUE")
    used_keys = used_keys_result[0] if used_keys_result else 0
    available_keys_result = db.fetch_one("SELECT COUNT(*) FROM keys WHERE used = FALSE")
    available_keys = available_keys_result[0] if available_keys_result else 0
    total_sales_result = db.fetch_one("SELECT COUNT(*) FROM sales")
    total_sales = total_sales_result[0] if total_sales_result else 0
    
    stats_text = f"""
📊 Bot Statistics

👥 Users:
• Total Users: {total_users}
• Verified Users: {verified_users}

🔑 Keys:
• Total Keys: {total_keys}
• Used Keys: {used_keys}
• Available Keys: {available_keys}

💰 Total Claims: {total_sales}
"""
    
    query.edit_message_text(stats_text, reply_markup=get_back_admin_keyboard())

def admin_all_users_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    users = db.fetch_all("SELECT user_id, username, verified, total_keys_claimed, first_seen FROM users ORDER BY first_seen DESC")
    
    if not users:
        query.edit_message_text("No users found.", reply_markup=get_back_admin_keyboard())
        return
    
    users_text = "👥 All Users:\n\n"
    for user_id, username, verified, keys_claimed, first_seen in users[:20]:
        status = "✅" if verified else "❌"
        users_text += f"{status} ID: {user_id} | @{username or 'N/A'} | Keys: {keys_claimed}\n"
    
    if len(users) > 20:
        users_text += f"\n... and {len(users) - 20} more users"
    
    query.edit_message_text(users_text, reply_markup=get_back_admin_keyboard())

def admin_add_keys_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
🔑 Add Keys

Send keys in one of these formats:

Format 1: key | duration | app_name
Example: ABC123 | 7d | Premium App
Example: XYZ789 | 24h | Basic

Format 2: key | app_name | duration | apk_link
Example: KEY456 | Pro App | 30days | https://example.com/app.apk

Duration formats:
• 24h, 12hours = Hours
• 7d, 30days = Days

You can send multiple keys (one per line).
"""
    
    set_user_state(ADMIN_ID, 'awaiting_keys')
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_add_channel_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
📢 Add Verification Channel

Send the channel username (with or without @):

Example: mychannel
Example: @mychannel
"""
    
    set_user_state(ADMIN_ID, 'awaiting_channel')
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_remove_channel_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    channels = get_verification_channels()
    if not channels:
        query.edit_message_text("No channels to remove.", reply_markup=get_back_admin_keyboard())
        return
    
    channel_text = "Current channels:\n\n"
    for channel, _ in channels:
        channel_text += f"• @{channel}\n"
    channel_text += "\nSend channel username to remove:"
    
    set_user_state(ADMIN_ID, 'awaiting_channel_remove')
    query.edit_message_text(channel_text, reply_markup=get_back_admin_keyboard())

def admin_list_channels_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    channels = get_verification_channels()
    if not channels:
        query.edit_message_text("📋 No channels configured.", reply_markup=get_back_admin_keyboard())
        return
    
    channel_text = "📋 Verification Channels:\n\n"
    for i, (channel, _) in enumerate(channels, 1):
        channel_text += f"{i}. @{channel}\n"
    
    query.edit_message_text(channel_text, reply_markup=get_back_admin_keyboard())

def admin_set_cooldown_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    current_cooldown = get_cooldown_hours()
    instructions = f"""
⏰ Set Cooldown Period

Current cooldown: {current_cooldown} hours

Send a number (1-720) to set new cooldown in hours:

Examples:
• 24 = 24 hours (1 day)
• 48 = 48 hours (2 days)
• 168 = 168 hours (1 week)
"""
    
    set_user_state(ADMIN_ID, 'awaiting_cooldown')
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_set_key_msg_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
💬 Set Key Message Template

Available placeholders:
• {key} - The actual key
• {duration} - Key duration
• {product} - Product name
• {link} - Product link

Send your custom message template:
"""
    
    set_user_state(ADMIN_ID, 'awaiting_key_message')
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_block_user_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    instructions = """
🚫 Block/Mute User

Send in format:
user_id | reason

Example:
123456789 | Violated terms of service

To unblock, send:
unblock user_id

Example:
unblock 123456789
"""
    
    set_user_state(ADMIN_ID, 'awaiting_block')
    query.edit_message_text(instructions, reply_markup=get_back_admin_keyboard())

def admin_announcement_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    query.edit_message_text(
        "📣 Send Announcement\n\nChoose announcement type:",
        reply_markup=get_announcement_type_keyboard()
    )

def announce_text_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    set_user_state(ADMIN_ID, 'awaiting_announcement_text')
    query.edit_message_text(
        "📝 Send your announcement message (text only):",
        reply_markup=get_back_admin_keyboard()
    )

def announce_photo_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    set_user_state(ADMIN_ID, 'awaiting_announcement_photo')
    query.edit_message_text(
        "🖼 Send a photo with caption for announcement:",
        reply_markup=get_back_admin_keyboard()
    )

def admin_waitlist_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    waitlist = get_waitlist_users()
    
    if not waitlist:
        query.edit_message_text("✅ Waitlist is empty!", reply_markup=get_back_admin_keyboard())
        return
    
    waitlist_text = "⏳ Users Waiting for Keys:\n\n"
    for user_id, username, added_at in waitlist[:30]:
        waitlist_text += f"• ID: {user_id} | @{username or 'N/A'}\n"
    
    if len(waitlist) > 30:
        waitlist_text += f"\n... and {len(waitlist) - 30} more users"
    
    waitlist_text += f"\n\n📊 Total: {len(waitlist)} user(s) waiting"
    
    query.edit_message_text(waitlist_text, reply_markup=get_back_admin_keyboard())

def admin_left_users_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    check_users_left_channels(context.bot)
    
    left_users = db.fetch_all('''
        SELECT DISTINCT u.user_id, u.username, s.assigned_at
        FROM sales s
        JOIN users u ON s.user_id = u.user_id
        WHERE s.left_channel = TRUE
        ORDER BY s.assigned_at DESC
    ''')
    
    if not left_users:
        query.edit_message_text("✅ No users have left after claiming keys.", reply_markup=get_back_admin_keyboard())
        return
    
    left_text = "🚪 Users Who Left After Claiming:\n\n"
    for user_id, username, assigned_at in left_users[:20]:
        left_text += f"• ID: {user_id} | @{username or 'N/A'}\n"
    
    if len(left_users) > 20:
        left_text += f"\n... and {len(left_users) - 20} more users"
    
    query.edit_message_text(left_text, reply_markup=get_back_admin_keyboard())

def admin_delete_all_keys_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    keyboard = [
        [
            InlineKeyboardButton("✅ Confirm Delete", callback_data="confirm_delete_all_keys"),
            InlineKeyboardButton("❌ Cancel", callback_data="admin_back_main")
        ]
    ]
    query.edit_message_text(
        "⚠️ Are you sure you want to delete ALL keys?\n\nThis action cannot be undone!",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )

def confirm_delete_all_keys_callback(update: Update, context: CallbackContext) -> None:
    query = update.callback_query
    query.answer()
    
    if query.from_user.id != ADMIN_ID:
        query.answer("Access denied", show_alert=True)
        return
    
    db.execute_query("DELETE FROM keys")
    query.edit_message_text("✅ All keys deleted successfully!", reply_markup=get_back_admin_keyboard())

# Process admin text input
def process_admin_text(update: Update, context: CallbackContext) -> None:
    if update.effective_user.id != ADMIN_ID:
        return
    
    text = update.message.text.strip()
    state = get_user_state(ADMIN_ID)
    
    if state['state'] == 'awaiting_keys':
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
            
            if len(parts) == 3:
                duration_value, duration_unit = parse_duration(parts[1])
                meta_name = parts[2]
                meta_link = ""
            elif len(parts) >= 4:
                meta_name = parts[1]
                duration_value, duration_unit = parse_duration(parts[2])
                meta_link = parts[3] if len(parts) > 3 else ""
            else:
                continue
            
            try:
                db.execute_query('''
                    INSERT INTO keys (key_text, duration_value, duration_unit, meta_name, meta_link)
                    VALUES (?, ?, ?, ?, ?)
                ''', (key_text, duration_value, duration_unit, meta_name, meta_link))
                keys_added += 1
            except sqlite3.IntegrityError:
                keys_duplicate += 1
        
        result_text = f"✅ Added {keys_added} keys"
        if keys_duplicate > 0:
            result_text += f"\n❌ {keys_duplicate} duplicate keys skipped"
        
        clear_user_state(ADMIN_ID)
        update.message.reply_text(result_text, reply_markup=get_back_admin_keyboard())
        
        # Process waitlist to auto-assign keys to waiting users
        if keys_added > 0:
            process_waitlist(context.bot)
        
        return
    
    elif state['state'] == 'awaiting_channel':
        channel_username = text.replace('@', '')
        try:
            db.execute_query("INSERT INTO channels (username) VALUES (?)", (channel_username,))
            clear_user_state(ADMIN_ID)
            update.message.reply_text(f"✅ Channel @{channel_username} added successfully!", reply_markup=get_back_admin_keyboard())
        except sqlite3.IntegrityError:
            update.message.reply_text(f"❌ Channel @{channel_username} already exists!", reply_markup=get_back_admin_keyboard())
        return
    
    elif state['state'] == 'awaiting_channel_remove':
        channel_username = text.replace('@', '')
        db.execute_query("DELETE FROM channels WHERE username = ?", (channel_username,))
        clear_user_state(ADMIN_ID)
        update.message.reply_text(f"✅ Channel @{channel_username} removed successfully!", reply_markup=get_back_admin_keyboard())
        return
    
    elif state['state'] == 'awaiting_cooldown':
        if text.isdigit() and 1 <= int(text) <= 720:
            db.execute_query("UPDATE settings SET value = ? WHERE key = 'cooldown_hours'", (text,))
            clear_user_state(ADMIN_ID)
            update.message.reply_text(f"✅ Cooldown set to {text} hours!", reply_markup=get_back_admin_keyboard())
        else:
            update.message.reply_text("❌ Please send a number between 1 and 720.", reply_markup=get_back_admin_keyboard())
        return
    
    elif state['state'] == 'awaiting_key_message':
        if '{key}' in text:
            db.execute_query("UPDATE settings SET value = ? WHERE key = 'key_message'", (text,))
            clear_user_state(ADMIN_ID)
            update.message.reply_text("✅ Key message updated successfully!", reply_markup=get_back_admin_keyboard())
        else:
            update.message.reply_text("❌ Message must contain {key} placeholder.", reply_markup=get_back_admin_keyboard())
        return
    
    elif state['state'] == 'awaiting_block':
        if text.lower().startswith('unblock'):
            user_id_to_unblock = int(text.split()[1])
            db.execute_query("UPDATE users SET blocked = FALSE, block_reason = '' WHERE user_id = ?", (user_id_to_unblock,))
            clear_user_state(ADMIN_ID)
            update.message.reply_text(f"✅ User {user_id_to_unblock} unblocked!", reply_markup=get_back_admin_keyboard())
        elif '|' in text:
            parts = text.split('|')
            user_id_to_block = int(parts[0].strip())
            reason = parts[1].strip() if len(parts) > 1 else "Blocked by admin"
            db.execute_query("UPDATE users SET blocked = TRUE, block_reason = ? WHERE user_id = ?", (reason, user_id_to_block))
            clear_user_state(ADMIN_ID)
            update.message.reply_text(f"✅ User {user_id_to_block} blocked!", reply_markup=get_back_admin_keyboard())
        else:
            update.message.reply_text("❌ Invalid format. Use: user_id | reason", reply_markup=get_back_admin_keyboard())
        return
    
    elif state['state'] == 'awaiting_announcement_text':
        all_users = db.fetch_all("SELECT user_id FROM users")
        sent = 0
        failed = 0
        
        for (user_id,) in all_users:
            try:
                context.bot.send_message(chat_id=user_id, text=f"📣 Announcement:\n\n{text}")
                sent += 1
            except:
                failed += 1
        
        clear_user_state(ADMIN_ID)
        update.message.reply_text(f"✅ Announcement sent to {sent} users!\n❌ Failed: {failed}", reply_markup=get_back_admin_keyboard())
        return

def process_admin_photo(update: Update, context: CallbackContext) -> None:
    if update.effective_user.id != ADMIN_ID:
        return
    
    state = get_user_state(ADMIN_ID)
    
    if state['state'] == 'awaiting_announcement_photo':
        photo = update.message.photo[-1]
        caption = update.message.caption or "📣 Announcement"
        
        all_users = db.fetch_all("SELECT user_id FROM users")
        sent = 0
        failed = 0
        
        for (user_id,) in all_users:
            try:
                context.bot.send_photo(chat_id=user_id, photo=photo.file_id, caption=caption)
                sent += 1
            except:
                failed += 1
        
        clear_user_state(ADMIN_ID)
        update.message.reply_text(f"✅ Announcement sent to {sent} users!\n❌ Failed: {failed}", reply_markup=get_back_admin_keyboard())

# Main function
def main():
    updater = Updater(BOT_TOKEN, use_context=True)
    dp = updater.dispatcher

    dp.add_handler(CommandHandler("start", start))
    dp.add_handler(CommandHandler("admin", admin_command))
    
    dp.add_handler(CallbackQueryHandler(verify_callback, pattern="^verify$"))
    dp.add_handler(CallbackQueryHandler(claim_callback, pattern="^start_claim$"))
    
    dp.add_handler(CallbackQueryHandler(admin_back_main_callback, pattern="^admin_back_main$"))
    dp.add_handler(CallbackQueryHandler(admin_stats_callback, pattern="^admin_stats$"))
    dp.add_handler(CallbackQueryHandler(admin_all_users_callback, pattern="^admin_all_users$"))
    dp.add_handler(CallbackQueryHandler(admin_add_keys_callback, pattern="^admin_add_keys$"))
    dp.add_handler(CallbackQueryHandler(admin_waitlist_callback, pattern="^admin_waitlist$"))
    dp.add_handler(CallbackQueryHandler(admin_add_channel_callback, pattern="^admin_add_channel$"))
    dp.add_handler(CallbackQueryHandler(admin_remove_channel_callback, pattern="^admin_remove_channel$"))
    dp.add_handler(CallbackQueryHandler(admin_list_channels_callback, pattern="^admin_list_channels$"))
    dp.add_handler(CallbackQueryHandler(admin_set_cooldown_callback, pattern="^admin_set_cooldown$"))
    dp.add_handler(CallbackQueryHandler(admin_set_key_msg_callback, pattern="^admin_set_key_msg$"))
    dp.add_handler(CallbackQueryHandler(admin_block_user_callback, pattern="^admin_block_user$"))
    dp.add_handler(CallbackQueryHandler(admin_announcement_callback, pattern="^admin_announcement$"))
    dp.add_handler(CallbackQueryHandler(announce_text_callback, pattern="^announce_text$"))
    dp.add_handler(CallbackQueryHandler(announce_photo_callback, pattern="^announce_photo$"))
    dp.add_handler(CallbackQueryHandler(admin_left_users_callback, pattern="^admin_left_users$"))
    dp.add_handler(CallbackQueryHandler(admin_delete_all_keys_callback, pattern="^admin_delete_all_keys$"))
    dp.add_handler(CallbackQueryHandler(confirm_delete_all_keys_callback, pattern="^confirm_delete_all_keys$"))
    
    dp.add_handler(MessageHandler(Filters.text & Filters.user(ADMIN_ID), process_admin_text))
    dp.add_handler(MessageHandler(Filters.photo & Filters.user(ADMIN_ID), process_admin_photo))

    Thread(target=run_flask, daemon=True).start()
    
    logger.info("Bot started!")
    updater.start_polling()
    updater.idle()

if __name__ == '__main__':
    main()
