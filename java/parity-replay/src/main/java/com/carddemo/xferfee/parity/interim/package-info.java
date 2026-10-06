/**
 * Stand-ins used by {@code parity-replay} only while the service modules are not on this branch
 * (COG-1235 fee-policy, COG-1236 fee-schedule-service, COG-1237 transfer-intake-service,
 * COG-1238 account-posting-service, COG-1239 reconciliation-service). They implement the
 * {@code contracts} SPIs from the COBOL source and BR-01..BR-19 far enough to produce each step's
 * SYSOUT, return code and events for the observability parity loop. They write no datasets, DB2
 * dumps or report lines; {@code ChainReplay} prefers any real contract bean over them.
 */
package com.carddemo.xferfee.parity.interim;
