# Pipelines

The fab step vocabulary: every step the queue can run,
in the canonical flow order. Each step declares what it reads, what it
writes, and its failure semantics. Branching/control steps (Branch,
OcapEvaluate, DynamicPorExecution, …) rewire the queue at runtime.
