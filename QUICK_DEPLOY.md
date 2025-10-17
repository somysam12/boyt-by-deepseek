# ⚡ Quick Deploy to Render - 5 Minutes

## 🎯 Fastest Way to Deploy

### Step 1: Update Files (2 min)

**1.1 Update requirements.txt:**
```bash
cp requirements_render.txt requirements.txt
```

**1.2 Add webhook support to main.py:**

Find the bottom of `main.py` (around line 1270) and **REPLACE**:
```python
# OLD CODE - DELETE THIS:
flask_thread = Thread(target=run_flask)
flask_thread.daemon = True
flask_thread.start()

updater.start_polling()
logger.info("Bot started!")
updater.idle()
```

With:
```python
# NEW CODE - ADD THIS:
@app.route('/webhook', methods=['POST'])
def webhook():
    update = Update.de_json(request.get_json(force=True), updater.bot)
    dp.process_update(update)
    return 'ok'

if __name__ == "__main__":
    if WEBHOOK_URL := os.getenv('WEBHOOK_URL'):
        updater.bot.set_webhook(url=f"{WEBHOOK_URL}/webhook")
        logger.info(f"Webhook set: {WEBHOOK_URL}/webhook")
    logger.info("Bot started!")
    app.run(host='0.0.0.0', port=PORT)
```

---

### Step 2: Push to GitHub (1 min)

```bash
git add .
git commit -m "Add Render webhook support"
git push origin main
```

---

### Step 3: Deploy on Render (2 min)

1. **Go to:** https://dashboard.render.com/new/web
2. **Connect your GitHub repo**
3. **Configure:**
   - Name: `telegram-key-bot`
   - Build: `pip install -r requirements.txt`
   - Start: `gunicorn main:app --bind 0.0.0.0:$PORT`
4. **Add Environment Variables:**
   ```
   BOT_TOKEN = <your bot token>
   ADMIN_ID = <your telegram user id>
   PYTHON_VERSION = 3.11.5
   ```
5. **Click "Create Web Service"**

---

### Step 4: Set Webhook URL (30 sec)

After deploy completes:
1. Copy your app URL: `https://your-app.onrender.com`
2. Add environment variable:
   ```
   WEBHOOK_URL = https://your-app.onrender.com
   ```
3. Save (auto-redeploys)

---

### Step 5: Test! ✅

Send `/start` to your bot on Telegram!

---

## 🔥 Even Faster: One-Line Deploy

Add this to end of main.py:
```python
@app.route('/webhook', methods=['POST'])
def webhook():
    update = Update.de_json(request.get_json(force=True), updater.bot)
    dp.process_update(update)
    return 'ok'

if __name__ == "__main__":
    WEBHOOK_URL = os.getenv('WEBHOOK_URL')
    if WEBHOOK_URL:
        updater.bot.set_webhook(f"{WEBHOOK_URL}/webhook")
        logger.info(f"Webhook: {WEBHOOK_URL}/webhook")
    app.run(host='0.0.0.0', port=PORT)
```

Then:
```bash
# Add gunicorn
echo "gunicorn==21.2.0" >> requirements.txt

# Push
git add . && git commit -m "render" && git push

# Deploy on Render with Start Command:
gunicorn main:app --bind 0.0.0.0:$PORT
```

Done! 🚀
