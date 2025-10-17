# Telegram Key Distribution Bot

## Overview
This is a Telegram bot for distributing keys/licenses to users who verify their membership in specified Telegram channels. The bot features a Flask health check endpoint running on port 5000 and uses SQLite for data storage.

## Project Architecture

### Technology Stack
- **Python 3.11**: Core language
- **python-telegram-bot 13.15**: Telegram bot API wrapper
- **Flask 2.3.3**: Web server for health checks and keep-alive
- **SQLite**: Database for users, keys, channels, and sales tracking
- **python-dotenv**: Environment variable management

### File Structure
```
├── main.py           # Main bot application
├── requirements.txt  # Python dependencies
├── .gitignore       # Git ignore patterns
└── replit.md        # This documentation
```

### Database Schema
The bot uses SQLite with the following tables:
- **channels**: Verification channels list
- **users**: User data and verification status
- **keys**: Available and used keys
- **sales**: Key assignment history
- **settings**: Bot configuration (cooldown, messages)
- **waitlist**: Users waiting for keys when unavailable

## Features

### User Features
- ✅ Channel membership verification with inline join buttons
- 🎁 Key claiming system with cooldown
- 🔑 Automatic key assignment (FIFO)
- ⏰ Enhanced cooldown display - shows detailed countdown with exact unlock date/time
- 🔄 Second claim support - claim new keys after cooldown expires
- 🚫 Blocked user handling with custom messages
- ⏳ Waitlist system - automatically added to queue when keys unavailable
- 📬 Auto-receive keys when admin adds new ones

### Admin Features
- 📊 Bot statistics and analytics
- 🔑 Flexible key addition (2 formats with hours/days duration)
- ⏳ Waitlist management - view all users waiting for keys
- 🔔 Automatic notifications when users join waitlist
- 🤖 Auto-assign keys to waitlist users when adding new keys
- 📢 Channel management (add/remove with inline buttons)
- ⏰ Cooldown configuration (1-720 hours)
- 🔄 Cooldown reset - instantly reset any user's cooldown
- 💬 Custom key message templates
- 👥 View all users with IDs and stats
- 🚪 Track users who left after claiming keys
- 🚫 Block/Mute users with custom reason messages
- 📣 Send announcements (text only or with photo)
- 🗑 Key management (delete all keys)

## Configuration

### Required Environment Variables
- `BOT_TOKEN`: Telegram bot token from @BotFather
- `ADMIN_ID`: Telegram user ID for admin access (numeric)
- `PORT`: Flask server port (default: 5000)

### Default Settings
- Cooldown: 24 hours between key claims
- Database: `/tmp/bot_database.db` (SQLite)
- Key message template: Customizable via admin panel

## Usage

### For Users
1. Start the bot with `/start`
2. Click inline buttons to join all required channels
3. Click "✅ Verify Membership" button
4. Click "🎁 Claim Key" to get your key
5. If cooldown is active, you'll see:
   - Detailed message: "Please wait for the cooldown to finish!"
   - Time remaining in hours and minutes
   - Exact date/time when you can claim next key
6. After cooldown expires, you can claim another key
7. If no keys available, you'll be automatically added to waitlist
8. Receive your key automatically when admin adds new keys

### For Admins
1. Use `/admin` command to access admin panel
2. **Add Keys** - Two flexible formats:
   - Format 1: `key | duration | app_name` (e.g., `ABC123 | 7d | Premium`)
   - Format 2: `key | app_name | duration | link` (e.g., `XYZ789 | Pro | 24h | https://example.com`)
   - Duration: Use `24h`, `12hours` for hours OR `7d`, `30days` for days
   - Keys automatically distributed to waitlist users
3. **Waitlist** - View all users waiting for keys
4. **Manage Channels** - Add/remove verification channels
5. **Set Cooldown** - Configure cooldown period (1-720 hours)
6. **Reset Cooldown** - Enter user ID to instantly reset their cooldown
7. **Block Users** - Format: `user_id | reason` or `unblock user_id`
8. **Send Announcements** - Text only or with photo to all users
9. **Track Users** - View all users, users who left after claiming
10. **Key Management** - View stats, delete all keys

## Development Setup
The bot automatically initializes the database on first run. The Flask server provides health check endpoints at:
- `/` - Returns "Bot is running!"
- `/health` - Returns "OK"

## Recent Changes
- **2025-10-17 (Latest Update)**: Enhanced cooldown features
  - **Cooldown Message Improvements:**
    - Enhanced cooldown display with detailed countdown message
    - Shows "Please wait for the cooldown to finish!" with exact time remaining
    - Displays unlock date/time when user can claim next key (format: YYYY-MM-DD HH:MM)
  - **Admin Cooldown Reset Feature:**
    - Added "🔄 Reset Cooldown" button in admin panel
    - Admin can instantly reset any user's cooldown by entering user ID
    - Validates user exists before resetting
    - User can claim key immediately after reset
  - **Second Claim Verification:**
    - Confirmed users can claim new keys after cooldown expires
    - No errors or restrictions on subsequent claims

- **2025-10-17**: Critical bug fixes and waitlist system
  - **Security Fixes:**
    - Fixed ADMIN_ID security vulnerability (removed default value of 0)
    - Added strict environment variable validation for BOT_TOKEN and ADMIN_ID
    - Added blocked user check in waitlist auto-assignment
  - **Waitlist System Implementation:**
    - Created waitlist database table
    - Users automatically added to waitlist when no keys available
    - Admin receives notification only when new users join waitlist
    - Auto-assign keys to waitlist users when admin adds new keys
    - Waitlist respects verification status, cooldown, and block status
    - Admin panel button to view waitlist
  - **Bug Fixes:**
    - Fixed claim process to properly check verification before cooldown
    - Prevented duplicate admin notifications for already-waitlisted users
    - Fixed blocked users receiving keys through waitlist auto-assignment
  - **UX Improvements:**
    - Different messages for newly waitlisted vs already-waitlisted users
    - Clear feedback when keys are auto-assigned from waitlist

- **2025-10-17**: Major feature update and Replit setup
  - Installed Python 3.11 and dependencies
  - Configured secrets (BOT_TOKEN, ADMIN_ID)
  - Set up Flask server on port 5000
  - Created workflow for bot execution
  - **New Features Added:**
    - Inline channel join buttons for users
    - Flexible key addition (2 formats, hours/days support)
    - Live cooldown countdown display
    - User tracking (all users, users who left after claiming)
    - Block/Mute system with custom reasons
    - Announcement system (text/photo to all users)
    - Enhanced admin panel with more controls
    - Better UI/UX with attractive inline buttons
  - **Database Updates:**
    - Added `blocked` and `block_reason` fields to users table
    - Added `left_channel` tracking to sales table
    - Added `duration_unit` support (hours/days) to keys table
    - Added `channel_link` field to channels table
    - Added `waitlist` table for queueing users

## Notes
- The bot runs continuously with Flask providing a health check endpoint
- Database is stored in `/tmp/` and will be cleared on Replit restart
- Keys are distributed FIFO (first in, first out) to waitlist users
- Admin access is restricted to the configured ADMIN_ID
- Waitlist users automatically receive keys when admin adds them
- Blocked users are removed from waitlist and cannot receive keys
