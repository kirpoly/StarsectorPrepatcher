Runtime StarsectorPrepatcher logs are written here. Target classes may load lazily; use the final
APPLIED/ALREADY_APPLIED/SKIPPED status summary instead of expecting a fixed line count.

When patch.directMarketObservation=true, each game launch creates:
  logs/direct-market-observe/session-<UTC>-pid<PID>/
Send the complete newest session directory together with logs/prepatcher.log for analysis.
Observation rows are flushed at directMarket.reportIntervalSeconds; let the game run for at least
one full interval after the last scenario before exiting.
