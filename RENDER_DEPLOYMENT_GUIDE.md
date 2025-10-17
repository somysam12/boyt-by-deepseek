# 🚀 Render.com Deployment Guide - Telegram Key Distribution Bot

## ⚠️ IMPORTANT: Webhook vs Polling

**Current Issue:** Your bot uses `polling` which **will NOT work** on Render.com because:
- Render requires HTTP endpoints for health checks
- Free tier sleeps after 15 minutes (polling stops)
- Multiple instances conflict during deployments

**Solution:** Use webhooks (HTTP-based updates from Telegram)

---

## 📋 Prerequisites

✅ GitHub account  
✅ Render.com account (free tier available)  
✅ Telegram Bot Token (BOT_TOKEN)  
✅ Admin Telegram ID (ADMIN_ID)

---

## 🔧 STEP 1: Prepare Your Bot for Render

### Option A: Quick Fix (Use Gunicorn with Flask Only)

Since your bot already has Flask, we'll use it as the main server:

**1. Update requirements.txt:**
```txt
python-telegram-bot==13.15
python-dotenv==1.0.0
flask==2.3.3
gunicorn==21.2.0
```

**2. Create `render.yaml` (Optional - for easier config):**
```yaml
services:
  - type: web
    name: telegram-key-bot
    env: python
    buildCommand: pip install -r requirements.txt
    startCommand: gunicorn main:app --bind 0.0.0.0:$PORT --workers 1
    envVars:
      - key: PYTHON_VERSION
        value: 3.11.5
```

**3. Modify main.py - Replace Polling with Webhook:**

Find this section at the bottom of `main.py`:
```python
# OLD CODE (Remove this)
flask_thread = Thread(target=run_flask)
flask_thread.daemon = True
flask_thread.start()

updater.start_polling()
logger.info("Bot started!")
updater.idle()
```

Replace with:
```python
# NEW CODE (Webhook mode)
@app.route('/webhook', methods=['POST'])
def webhook():
    """Handle Telegram updates via webhook"""
    try:
        update = Update.de_json(request.get_json(force=True), updater.bot)
        dp.process_update(update)
        return 'ok', 200
    except Exception as e:
        logger.error(f"Webhook error: {e}")
        return 'error', 500

def setup_webhook():
    """Set webhook URL on Telegram"""
    webhook_url = os.getenv('WEBHOOK_URL', '')
    if webhook_url:
        updater.bot.set_webhook(url=f"{webhook_url}/webhook")
        logger.info(f"Webhook set to: {webhook_url}/webhook")
    else:
        logger.warning("WEBHOOK_URL not set!")

if __name__ == "__main__":
    # Initialize bot and dispatcher
    updater = Updater(BOT_TOKEN)
    dp = updater.dispatcher
    
    # Add all handlers (keep your existing handler setup)
    # ... (your existing handler code)
    
    # Setup webhook
    setup_webhook()
    
    # Run Flask app (Gunicorn will use this)
    logger.info("Bot started in webhook mode!")
    app.run(host='0.0.0.0', port=PORT)
```

---

## 📦 STEP 2: Push Code to GitHub

```bash
# Initialize git (if not done)
git init

# Add all files
git add .

# Commit
git commit -m "Prepare bot for Render deployment"

# Add remote (create repo on GitHub first)
git remote add origin https://github.com/YOUR_USERNAME/telegram-key-bot.git

# Push
git push -u origin main
```

---

## 🌐 STEP 3: Deploy on Render.com

### A. Create Web Service

1. **Go to:** https://dashboard.render.com
2. **Click:** "New +" → "Web Service"
3. **Connect GitHub:** Authorize Render to access your repos
4. **Select Repository:** Choose your telegram-key-bot repo

### B. Configure Service

| Setting | Value |
|---------|-------|
| **Name** | `telegram-key-bot` (or any unique name) |
| **Region** | Singapore / Frankfurt (closest to you) |
| **Branch** | `main` |
| **Root Directory** | (leave blank) |
| **Environment** | `Python 3` |
| **Build Command** | `pip install -r requirements.txt` |
| **Start Command** | `gunicorn main:app --bind 0.0.0.0:$PORT --workers 1 --timeout 120` |
| **Plan** | Free (or Starter $7/month) |

### C. Add Environment Variables

Click **"Advanced"** → **"Add Environment Variable"**

Add these 3 variables:

| Key | Value | Example |
|-----|-------|---------|
| `BOT_TOKEN` | Your bot token from @BotFather | `7362748392:AAH...` |
| `ADMIN_ID` | Your Telegram user ID | `123456789` |
| `WEBHOOK_URL` | (see below - add AFTER deploy) | `https://telegram-key-bot.onrender.com` |
| `PYTHON_VERSION` | `3.11.5` | `3.11.5` |

**⚠️ IMPORTANT:** Leave `WEBHOOK_URL` empty for now. We'll add it after first deployment.

### D. Deploy!

1. Click **"Create Web Service"**
2. Wait 2-5 minutes for build
3. Check logs for errors

---

## 🔗 STEP 4: Set Webhook URL

After deployment succeeds:

1. **Copy your Render URL:** `https://your-app-name.onrender.com`
2. **Go to:** Environment Variables in Render dashboard
3. **Add/Update:** `WEBHOOK_URL` = `https://your-app-name.onrender.com`
4. **Save Changes** (this will auto-redeploy)

---

## ✅ STEP 5: Verify Deployment

### Check if bot is working:

1. **Visit your URL:** `https://your-app-name.onrender.com`
   - Should show: "Bot is running!"

2. **Check health endpoint:** `https://your-app-name.onrender.com/health`
   - Should show: "OK"

3. **Test bot on Telegram:**
   - Send `/start` to your bot
   - Should receive welcome message

4. **Check Render logs:**
   - Go to: Dashboard → Your Service → Logs
   - Look for: "Webhook set to: ..."
   - Should see: "Bot started in webhook mode!"

---

## 🐛 Troubleshooting

### Issue 1: "Conflict: terminated by other getUpdates"
**Fix:** You're still using polling. Make sure you removed `updater.start_polling()` and added webhook code.

### Issue 2: "Application failed to respond"
**Fix:** 
- Check Start Command uses `gunicorn` not `python main.py`
- Ensure PORT binding: `app.run(host='0.0.0.0', port=PORT)`

### Issue 3: "Module not found"
**Fix:** 
- Verify `requirements.txt` has all dependencies
- Check Build Command: `pip install -r requirements.txt`

### Issue 4: Bot sleeps after 15 minutes (Free tier)
**Fix:** 
- Upgrade to Starter plan ($7/month) for 24/7 uptime
- Or use external service to ping bot every 10 minutes (cron-job.org)

### Issue 5: Database resets on redeploy
**Fix:** `/tmp/` is ephemeral. Consider:
- Using Render's PostgreSQL (free tier available)
- Or external DB like MongoDB Atlas (free)

---

## 💰 Pricing (2025)

| Plan | Cost | Features |
|------|------|----------|
| **Free** | $0 | 750 hours/month, sleeps after 15min, limited resources |
| **Starter** | $7/month | Always-on, 0.5 GB RAM, better performance |
| **Standard** | $25/month | 2 GB RAM, autoscaling, priority support |

---

## 📊 Monitor Your Bot

### View Logs:
```
Dashboard → Your Service → Logs (real-time)
```

### Check Metrics:
```
Dashboard → Metrics → CPU, Memory, Requests
```

### Manual Deploy:
```
Dashboard → Manual Deploy (to force redeploy)
```

---

## 🔒 Security Best Practices

1. ✅ Never commit `.env` file (use Render's environment variables)
2. ✅ Use HTTPS webhook URLs only
3. ✅ Validate incoming webhook requests (optional advanced)
4. ✅ Set `SECRET_TOKEN` for webhook validation (advanced)

---

## 📱 Quick Deploy Checklist

- [ ] Modified main.py to use webhooks (removed polling)
- [ ] Added `gunicorn` to requirements.txt
- [ ] Pushed code to GitHub
- [ ] Created Web Service on Render
- [ ] Set Build Command: `pip install -r requirements.txt`
- [ ] Set Start Command: `gunicorn main:app --bind 0.0.0.0:$PORT --workers 1`
- [ ] Added environment variables: BOT_TOKEN, ADMIN_ID, PYTHON_VERSION
- [ ] Deployed and got Render URL
- [ ] Added WEBHOOK_URL environment variable with Render URL
- [ ] Verified bot works on Telegram
- [ ] Checked logs for successful webhook setup

---

## 🎯 Alternative: Use Background Worker (Advanced)

If you want to keep polling:
- Deploy as **Background Worker** (not Web Service)
- No health checks needed
- Always runs (even on free tier)
- Start Command: `python main.py`

But webhooks are **recommended** for better reliability and resource usage.

---

## 📞 Support

**Render Docs:** https://render.com/docs  
**python-telegram-bot:** https://docs.python-telegram-bot.org  
**Render Community:** https://community.render.com

---

Your bot should be live at `https://your-app-name.onrender.com` 🚀

**Test it:** Send `/start` to your bot on Telegram!
