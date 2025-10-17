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
- ✅ Channel membership verification
- 🎁 Key claiming system with cooldown
- 🔑 Automatic key assignment (FIFO)

### Admin Features
- 📊 Bot statistics and analytics
- 🔑 Bulk key addition
- 📢 Channel management (add/remove)
- ⏰ Cooldown configuration
- 💬 Custom key message templates
- 👥 User history tracking
- 🗑 Key management

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
2. Join all required channels
3. Click "Verify Membership"
4. Claim your key

### For Admins
1. Use `/admin` command to access admin panel
2. Add keys in format: `key | duration_days | product_name | product_link`
3. Manage channels and settings through admin interface

## Development Setup
The bot automatically initializes the database on first run. The Flask server provides health check endpoints at:
- `/` - Returns "Bot is running!"
- `/health` - Returns "OK"

## Recent Changes
- **2025-10-17**: Initial Replit setup
  - Installed Python 3.11 and dependencies
  - Configured secrets (BOT_TOKEN, ADMIN_ID)
  - Set up Flask server on port 5000
  - Created workflow for bot execution
  - Added .gitignore for Python project

## Notes
- The bot runs continuously with Flask providing a health check endpoint
- Database is stored in `/tmp/` and will be cleared on Replit restart
- Keys are distributed FIFO (first in, first out)
- Admin access is restricted to the configured ADMIN_ID
