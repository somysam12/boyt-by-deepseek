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

## Features

### User Features
- ✅ Channel membership verification with inline join buttons
- 🎁 Key claiming system with cooldown
- 🔑 Automatic key assignment (FIFO)
- ⏰ Live cooldown countdown (shows remaining time when trying to claim)
- 🚫 Blocked user handling with custom messages

### Admin Features
- 📊 Bot statistics and analytics
- 🔑 Flexible key addition (2 formats with hours/days duration)
- 📢 Channel management (add/remove with inline buttons)
- ⏰ Cooldown configuration (1-720 hours)
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
5. If cooldown is active, you'll see remaining time in hours:minutes format

### For Admins
1. Use `/admin` command to access admin panel
2. **Add Keys** - Two flexible formats:
   - Format 1: `key | duration | app_name` (e.g., `ABC123 | 7d | Premium`)
   - Format 2: `key | app_name | duration | link` (e.g., `XYZ789 | Pro | 24h | https://example.com`)
   - Duration: Use `24h`, `12hours` for hours OR `7d`, `30days` for days
3. **Manage Channels** - Add/remove verification channels
4. **Set Cooldown** - Configure cooldown period (1-720 hours)
5. **Block Users** - Format: `user_id | reason` or `unblock user_id`
6. **Send Announcements** - Text only or with photo to all users
7. **Track Users** - View all users, users who left after claiming
8. **Key Management** - View stats, delete all keys

## Development Setup
The bot automatically initializes the database on first run. The Flask server provides health check endpoints at:
- `/` - Returns "Bot is running!"
- `/health` - Returns "OK"

## Recent Changes
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

## Notes
- The bot runs continuously with Flask providing a health check endpoint
- Database is stored in `/tmp/` and will be cleared on Replit restart
- Keys are distributed FIFO (first in, first out)
- Admin access is restricted to the configured ADMIN_ID
