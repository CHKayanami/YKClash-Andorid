use clash_lib::{app::logging::{self, EventCollector, LogEvent, LoggingGuard}, config::def::LogLevel};
use std::sync::OnceLock;
use tokio::sync::broadcast::{self, Sender};

static LOGGING: OnceLock<Result<(LoggingGuard, Sender<LogEvent>), String>> = OnceLock::new();

pub fn channel(level: LogLevel, cwd: &str) -> Result<Sender<LogEvent>, String> {
    // The tracing subscriber is process-global. Reuse its sender across core
    // restarts so the collector and each new API instance share one channel.
    let state = LOGGING.get_or_init(|| {
        let (sender, _) = broadcast::channel(1000);
        let collector = EventCollector::new(vec![sender.clone()]);
        logging::setup_logging(level, collector, cwd, None)
            .map(|guard| (guard, sender)).map_err(|error| error.to_string())
    });
    match state {
        Ok((_, sender)) => {
            logging::set_log_level(level).map_err(|error| error.to_string())?;
            Ok(sender.clone())
        }
        Err(error) => Err(error.clone()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collector_publishes_to_the_channel_reused_after_restart() {
        let first = channel(LogLevel::Info, ".").unwrap();
        let mut receiver = first.subscribe();
        let restarted = channel(LogLevel::Info, ".").unwrap();
        assert!(first.same_channel(&restarted));
        tracing::warn!(target: "clash", "android log transport verification");
        let event = receiver.try_recv().unwrap();
        assert!(event.msg.contains("android log transport verification"));
    }
}
