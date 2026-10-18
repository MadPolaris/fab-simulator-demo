package net.imadz.application.services.transactor

import net.imadz.application.aggregates.repository.LotRepository

/** Shared execution context for sagas running on this node's v3 engine.
  * The engine binds one context per coordinator pool and hands it to every phase
  * invocation: fab-saga reads `lots`. (2026-10-18: bank `creditBalances` removed —
  * the CreditBalance/MonthlyIncome cluster was dead code from the playAkkaCQRS move.) */
final case class AppSagaContext(lots: LotRepository)
