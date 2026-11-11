brainyard's agent runtime, on jolt, with its loops as data

a small kernel does the mechanical things: tick a behavior tree, call a provider, run a tool, assemble a prompt from a signature
everything the agent does is data loaded at runtime: loop trees, predictor specs, prompts — and per-project params that tune them without a rebuild
leaf fns are the plugin unit; a tree node declares what its fn works on (which predictor, which prompt zones, which memory keys)
loops nest: the session loop runs a whole react loop per turn inside a scope; the same shape takes a feature loop holding implementer and reviewer loops
predictors are declarative (id, strategy, instructions, fields); params are compiled data (instructions, field descriptions, demos), and an optimizer fed by the :predictor/call trace writes them
brainyard stays the reference: port faithfully, measure against it by replaying its logged requests, record every intentional difference
jvm dependencies are replaced with jolt-native ones (http-client, process, db, ebb), never shimmed around

later: the code channel, memory, streaming, persistence, a supervisor that watches the loops and rewrites their trees behind validation
UI is a separate layer that attaches to the session loop's read-input / emit seam
