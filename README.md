# TaskBot

A lean Telegram task manager bot in Java.

## Features
- create tasks
- priority
- custom categories
- recurring tasks
- stale task detection
- reminder timings
- morning summary
- mark done so completed tasks disappear from active interface
- SQLite persistence
- suitable for running on a VM with `supervisorctl`

## Commands
- `/start`
- `/help`
- `/add title | priority | category | yyyy-MM-dd HH:mm | recurrence`
- `/tasks`
- `/today`
- `/overdue`
- `/stale`
- `/done <taskId>`
- `/snooze <taskId> <hours>`
- `/doneitems`
- `/addcategory <name>`
- `/categories`
- `/review`

## Example
```text
/add Finish CPM assignment | high | school | 2026-03-25 20:00 | weekly
```

You can also use:
- `today 20:00`
- `tomorrow 8PM`

## Token and config
You can configure the bot in either of these ways:

### Option 1: `application.properties`
Edit:
`src/main/resources/application.properties`

### Option 2: environment variables
Environment variables override the properties file.

```bash
export BOT_USERNAME="your_bot_username"
export BOT_TOKEN="123456:ABCDEF-your-real-token"
export APP_TIMEZONE="Asia/Singapore"
export APP_MORNING_SUMMARY_TIME="08:00"
export APP_SCHEDULER_INTERVAL_SECONDS="60"
export APP_DB_PATH="data/taskbot.db"
export APP_DEFAULT_STALE_DAYS="5"
```

## Build
```bash
mvn clean package
```

This creates a runnable fat jar in `target/`.

## Run
```bash
java -jar target/taskbot-1.0.0.jar
```

## Supervisor example
See `supervisor/taskbot.conf`
