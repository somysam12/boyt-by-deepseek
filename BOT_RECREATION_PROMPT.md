# Telegram Key Distribution Bot - Complete Recreation Prompt

Create a fully functional Telegram Key Distribution Bot with the following exact specifications:

## 📋 OVERVIEW
A Telegram bot that distributes keys/licenses to users after they verify membership in specified channels. The bot includes admin controls, waitlist system, cooldown management, and comprehensive user tracking.

## 🛠️ TECHNOLOGY STACK
- **Python 3.11**
- **python-telegram-bot 13.15** (Telegram bot API wrapper)
- **Flask 2.3.3** (Web server for health checks, running on port 5000)
- **SQLite** (Database stored at `/tmp/bot_database.db`)
- **python-dotenv** (Environment variable management)

## 📁 FILE STRUCTURE
```
project-root/
├── main.py              # Main bot application (all code in one file)
├── requirements.txt     # Python dependencies
├── .gitignore          # Git ignore patterns
└── replit.md           # Project documentation
```

## 📦 DEPENDENCIES (requirements.txt)
```
python-telegram-bot==13.15
python-dotenv==1.0.0
flask==2.3.3
```

## 🔐 ENVIRONMENT VARIABLES
Required secrets (stored in .env or environment):
- `BOT_TOKEN` - Telegram bot token from @BotFather (required)
- `ADMIN_ID` - Telegram user ID for admin access, numeric (required)
- `PORT` - Flask server port (default: 5000)

**IMPORTANT:** The code must validate that BOT_TOKEN and ADMIN_ID exist, raising ValueError if missing.

## 🗄️ DATABASE SCHEMA

### Table: channels
```sql
CREATE TABLE channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    channel_link TEXT DEFAULT ''
)
```

### Table: users
```sql
CREATE TABLE users (
    user_id INTEGER PRIMARY KEY,
    username TEXT,
    verified BOOLEAN DEFAULT FALSE,
    last_key_time TIMESTAMP,
    total_keys_claimed INTEGER DEFAULT 0,
    first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    blocked BOOLEAN DEFAULT FALSE,
    block_reason TEXT DEFAULT ''
)
```

### Table: keys
```sql
CREATE TABLE keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_text TEXT UNIQUE NOT NULL,
    duration_value INTEGER DEFAULT 30,
    duration_unit TEXT DEFAULT 'days',
    meta_name TEXT DEFAULT 'Premium',
    meta_link TEXT DEFAULT '',
    used BOOLEAN DEFAULT FALSE,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)
```

### Table: sales
```sql
CREATE TABLE sales (
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
```

### Table: settings
```sql
CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT
)
```
Default settings:
- `cooldown_hours` = '24'
- `key_message` = '🎉 Congratulations! Your key has been assigned:\n\n🔑 Key: {key}\n⏰ Duration: {duration}\n📦 Product: {product}\n🔗 Link: {link}'
- `awaiting_admin_action` = ''

### Table: waitlist
```sql
CREATE TABLE waitlist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    username TEXT,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    notified_admin BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (user_id) REFERENCES users (user_id)
)
```

## ✨ USER FEATURES

### 1. /start Command
- Welcome message with instructions
- Inline buttons to join all required channels
- "✅ Verify Membership" button
- "🎁 Claim Key" button

### 2. Channel Verification
- User must join all required channels
- Inline buttons with URLs: `https://t.me/{channel_username}`
- Verify using Telegram API `get_chat_member()`
- Check for statuses: 'member', 'administrator', 'creator'
- If no channels configured, auto-verify user

### 3. Key Claiming Process
**Flow:**
1. Check if user is blocked → Show block reason
2. Check if user is verified → Require verification first
3. Check cooldown:
   - If active: Show detailed message with:
     ```
     ⏰ Please wait for the cooldown to finish!
     
     🕐 Time Remaining: X hours Y minutes
     
     ⏳ You can claim your next key at:
     📅 YYYY-MM-DD HH:MM
     ```
4. Check if keys available:
   - If YES: Assign key immediately
   - If NO: Add to waitlist automatically

### 4. Waitlist System
- Auto-add user when no keys available
- Different messages for new vs already-waitlisted users
- Auto-receive key when admin adds new keys
- Respects verification, cooldown, and block status

### 5. Blocked User Handling
- Custom block reason messages
- Cannot claim keys or join waitlist when blocked

## 🔧 ADMIN FEATURES

### Admin Panel (/admin command)
Admin keyboard layout:
```
[📊 Statistics] [👥 All Users]
[🔑 Add Keys] [⏳ Waitlist]
[📢 Add Channel] [🗑 Remove Channel]
[📋 List Channels]
[⏰ Set Cooldown] [💬 Set Key Message]
[🔄 Reset Cooldown (User)] [🔄 Reset All Cooldown]
[🚫 Block User] [✅ Unblock User]
[📣 Send Announcement]
[🚪 Users Who Left] [❌ Delete All Keys]
```

### 1. Statistics
Show:
- Total users
- Verified users
- Total keys added
- Keys used
- Keys remaining
- Total sales
- Current cooldown hours

### 2. All Users
Display all users with:
- User ID
- Username
- Verified status
- Keys claimed count
- Limited to 50 users per message

### 3. Add Keys (Two Formats)
**Format 1:** `key | duration | app_name`
- Example: `ABC123 | 7d | Premium`

**Format 2:** `key | app_name | duration | link`
- Example: `XYZ789 | Pro | 24h | https://example.com`

**Duration parsing:**
- Supports: `24h`, `12hours` → hours
- Supports: `7d`, `30days` → days
- Must parse and store duration_value and duration_unit separately

**After adding keys:**
- Process waitlist automatically
- Assign keys to waiting users (FIFO)
- Send keys to users automatically

### 4. Waitlist Management
- View all users waiting for keys
- Show user ID, username, added time
- Display total count
- Limit to 30 users per message

### 5. Channel Management
**Add Channel:**
- Accept @username or username
- Store in database
- Handle duplicates gracefully

**Remove Channel:**
- Accept @username or username
- Delete from database

**List Channels:**
- Show all verification channels
- Display with @ prefix

### 6. Cooldown Management

**Set Cooldown:**
- Accept hours (1-720)
- Update in settings table

**Reset Cooldown (User):**
- Accept user ID
- Validate user exists
- Set last_key_time = NULL
- User can claim immediately

**Reset All Cooldown:**
- Show confirmation dialog first
- Reset cooldown for ALL users
- Display count of affected users
- Buttons: [✅ Confirm Reset All] [❌ Cancel]

### 7. Block/Unblock System

**Block User (Separate Button):**
- Format: `user_id | reason`
- Example: `123456789 | Spam`
- Validate user exists
- Store block reason
- Show confirmation with reason

**Unblock User (Separate Button):**
- Accept user ID only
- Validate user exists
- Check if actually blocked
- Clear block_reason
- Show appropriate message

### 8. Key Message Template
- Must contain {key} placeholder
- Available placeholders: {key}, {duration}, {product}, {link}
- Validate presence of {key}

### 9. Announcements

**Text Only:**
- Send text message to all users
- Show success count

**With Photo:**
- Accept photo with caption
- Send to all users
- Show success count

### 10. Users Who Left
- Check channel membership for all active sales
- Update left_channel = TRUE if user left
- Display list of users who left after claiming

### 11. Delete All Keys
- Show confirmation dialog
- Delete all keys from database
- Buttons: [✅ Confirm Delete] [❌ Cancel]

## 🔄 CALLBACK HANDLERS (ALL REQUIRED)

### User Callbacks
- `verify` → verify_callback
- `start_claim` → claim_callback

### Admin Callbacks
- `admin_back_main` → admin_back_main_callback
- `admin_stats` → admin_stats_callback
- `admin_all_users` → admin_all_users_callback
- `admin_add_keys` → admin_add_keys_callback
- `admin_waitlist` → admin_waitlist_callback
- `admin_add_channel` → admin_add_channel_callback
- `admin_remove_channel` → admin_remove_channel_callback
- `admin_list_channels` → admin_list_channels_callback
- `admin_set_cooldown` → admin_set_cooldown_callback
- `admin_set_key_msg` → admin_set_key_msg_callback
- `admin_reset_cooldown` → admin_reset_cooldown_callback
- `admin_reset_all_cooldown` → admin_reset_all_cooldown_callback
- `confirm_reset_all_cooldown` → confirm_reset_all_cooldown_callback
- `admin_block_user` → admin_block_user_callback
- `admin_unblock_user` → admin_unblock_user_callback
- `admin_announcement` → admin_announcement_callback
- `announce_text` → announce_text_callback
- `announce_photo` → announce_photo_callback
- `admin_left_users` → admin_left_users_callback
- `admin_delete_all_keys` → admin_delete_all_keys_callback
- `confirm_delete_all_keys` → confirm_delete_all_keys_callback

## 📝 USER STATE MANAGEMENT

Use in-memory dictionary: `user_states = {}`

States to implement:
- `awaiting_keys` - For adding keys
- `awaiting_channel` - For adding channel
- `awaiting_channel_remove` - For removing channel
- `awaiting_cooldown` - For setting cooldown
- `awaiting_key_message` - For setting key message
- `awaiting_block` - For blocking user
- `awaiting_unblock` - For unblocking user
- `awaiting_cooldown_reset` - For resetting user cooldown
- `awaiting_announcement_text` - For text announcement
- `awaiting_announcement_photo` - For photo announcement

State functions:
```python
def set_user_state(user_id: int, state: str, data: Any = None)
def get_user_state(user_id: int)
def clear_user_state(user_id: int)
```

## 🔑 KEY UTILITY FUNCTIONS

### parse_duration(duration_str: str) -> Tuple[int, str]
Parse formats: '24h', '7d', '30days', '12hours'
Return: (value, unit) where unit is 'hours' or 'days'

### format_duration(duration_value: int, duration_unit: str) -> str
Format for display:
- If hours >= 24: "X days Y hours"
- If hours < 24: "X hours"
- If days: "X days"

### get_duration_in_hours(duration_value: int, duration_unit: str) -> int
Convert to hours for expiry calculation

### assign_key_to_user(user_id, username, key_data) -> Dict
- Mark key as used
- Update user (verified=TRUE, last_key_time, total_keys_claimed)
- Create sale record
- Calculate expires_at
- Return dict with key, duration, product, link, expires_at

## 🔄 WAITLIST PROCESSING

### process_waitlist(bot) function
Called after adding keys:
1. Get all waitlist users (ordered by added_at ASC)
2. For each user:
   - Check if blocked → remove from waitlist
   - Check if verified → remove if not verified
   - Check cooldown → skip if in cooldown
   - Get available key → break if none
   - Assign key to user
   - Send key message to user
   - Remove from waitlist
3. Notify admin of assigned count

### notify_admin_waitlist(bot, user_id, username)
Send notification to admin when user joins waitlist:
```
⚠️ User Added to Waitlist!

👤 User ID: {user_id}
📝 Username: @{username}
📋 Waitlist Size: {count} user(s)

💡 Add keys using /admin → Add Keys
```

## 🌐 FLASK SERVER

### Setup
```python
app = Flask(__name__)

@app.route('/')
def home():
    return "Bot is running!"

@app.route('/health')
def health():
    return "OK"

def run_flask():
    app.run(host='0.0.0.0', port=PORT)
```

### Run in Thread
Start Flask in separate thread:
```python
flask_thread = Thread(target=run_flask)
flask_thread.daemon = True
flask_thread.start()
```

## 🤖 BOT INITIALIZATION

### Main Function Structure
```python
def main():
    # Load environment variables
    # Validate BOT_TOKEN and ADMIN_ID exist
    # Initialize database
    # Create updater and dispatcher
    # Add all handlers (command, callback, message)
    # Start Flask thread
    # Start bot polling
    # Run until interrupted
```

### Handler Order (IMPORTANT)
1. Command handlers (/start, /admin)
2. Callback query handlers (all callbacks)
3. Message handlers:
   - `MessageHandler(Filters.text & Filters.user(ADMIN_ID), process_admin_text)`
   - `MessageHandler(Filters.photo & Filters.user(ADMIN_ID), process_admin_photo)`

## 📊 MESSAGE PROCESSING

### process_admin_text(update, context)
Handle all admin text states:
- awaiting_keys → Parse and add keys
- awaiting_channel → Add channel
- awaiting_channel_remove → Remove channel
- awaiting_cooldown → Set cooldown (1-720)
- awaiting_key_message → Set message template (must have {key})
- awaiting_block → Block user (user_id | reason)
- awaiting_unblock → Unblock user (user_id)
- awaiting_cooldown_reset → Reset user cooldown (user_id)
- awaiting_announcement_text → Send announcement to all

### process_admin_photo(update, context)
Handle awaiting_announcement_photo:
- Get photo and caption
- Send to all users
- Show success count

## 🔒 SECURITY REQUIREMENTS

1. All admin functions must check: `if user_id != ADMIN_ID`
2. Blocked users cannot:
   - Claim keys
   - Receive keys from waitlist
   - Join waitlist (remove if blocked)
3. Validate environment variables on startup
4. Use proper error handling for database operations
5. Handle Telegram API errors gracefully

## 🎨 UI/UX REQUIREMENTS

### Inline Keyboards
- Use InlineKeyboardButton and InlineKeyboardMarkup
- Create separate functions:
  - `get_main_keyboard(bot, user_id)`
  - `get_admin_keyboard()`
  - `get_back_admin_keyboard()`
  - `get_announcement_type_keyboard()`

### Messages
- Use emojis for visual appeal
- Clear, concise instructions
- Show user feedback for all actions
- Use `show_alert=True` for important popups
- Format dates as: YYYY-MM-DD HH:MM

### Error Handling
- Invalid format → Show example
- User not found → Clear error message
- Duplicate entries → Handle gracefully
- Database errors → Log and inform user

## 📝 LOGGING

Setup logging:
```python
logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO
)
logger = logging.getLogger(__name__)
```

Log important events:
- Database initialization
- Bot started
- Key additions
- Waitlist processing
- Errors

## 🚀 EXECUTION FLOW

1. Load environment variables (load_dotenv())
2. Validate BOT_TOKEN and ADMIN_ID
3. Initialize Flask app
4. Initialize database (create all tables, insert default settings)
5. Create updater with BOT_TOKEN
6. Get dispatcher
7. Add all handlers in correct order
8. Start Flask in daemon thread
9. Start bot with updater.start_polling()
10. Log "Bot started!"
11. updater.idle()

## ✅ TESTING CHECKLIST

After implementation, verify:
- [ ] /start shows welcome with channel join buttons
- [ ] Verify Membership works correctly
- [ ] Claim Key respects verification, cooldown, blocks
- [ ] Cooldown shows exact time remaining
- [ ] Waitlist auto-adds when no keys
- [ ] Admin panel shows all buttons correctly
- [ ] Add Keys (both formats) works
- [ ] Reset Cooldown (User) works
- [ ] Reset All Cooldown with confirmation works
- [ ] Block User (separate) works with validation
- [ ] Unblock User (separate) works with validation
- [ ] Waitlist processing distributes keys
- [ ] Admin notifications work
- [ ] Announcements (text/photo) work
- [ ] Flask server runs on port 5000
- [ ] Database persists data correctly
- [ ] All error handling works

## 📌 CRITICAL IMPLEMENTATION NOTES

1. **Single File Structure:** All code in main.py (no separate modules)
2. **Thread Safety:** Use sqlite3 connection per operation (open/close each time)
3. **Time Handling:** Use datetime.now() and fromisoformat() for timestamps
4. **Duration Parsing:** Must support both 'h'/'hours' and 'd'/'days'
5. **Waitlist FIFO:** Process in order by added_at ASC
6. **Separate Block/Unblock:** Two different buttons and states
7. **Confirmation Dialogs:** For Reset All Cooldown and Delete All Keys
8. **User Validation:** Check user exists in database before operations
9. **Admin-Only Access:** Every admin function must verify ADMIN_ID
10. **Flask Threading:** Must run in daemon thread to not block bot

## 🔗 COMPLETE FILE CONTENTS

### requirements.txt
```
python-telegram-bot==13.15
python-dotenv==1.0.0
flask==2.3.3
```

### .gitignore
```
__pycache__/
*.pyc
.env
.pythonlibs/
.cache/
.config/
.upm/
venv/
*.db
.local/
```

---

## 💡 USAGE INSTRUCTIONS

**For Users:**
1. Start bot with /start
2. Join all channels via inline buttons
3. Click "Verify Membership"
4. Click "Claim Key" to get key
5. Wait for cooldown to claim next key

**For Admin:**
1. Send /admin to open panel
2. Add keys in specified formats
3. Manage channels, cooldown, users
4. Block/unblock users separately
5. Reset cooldown individually or for all
6. Send announcements to users
7. Monitor waitlist and statistics

---

**IMPORTANT:** This is a complete specification. Every feature, function, callback, and detail must be implemented exactly as described. The bot must handle all edge cases, validate all inputs, and provide clear user feedback for all operations.
