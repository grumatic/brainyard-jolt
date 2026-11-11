(ns brainyard.dev.team-dispatch-spike
  "Measurements behind RFC-032's task-board decision (byj-gly.253.11): does a
  team need a board its members pull work from, or does the leader assigning
  work do as well?

      jolt -A:dev -m brainyard.dev.team-dispatch-spike

  A simulation of scheduling, not of models. What differs between the
  policies is WHEN a member gets its next task, and what that costs; how long
  a task takes is the same draw for every policy in a trial. Three policies:

  - `upfront`: the leader splits every task among the members in one turn,
    round-robin, into each member's channel queue (it holds ten). No further
    leader turns; a member that draws long tasks finishes last.
  - `wakeup`: the leader assigns one task per member, then assigns the next
    each time an answer lands (task$wakeup) — which costs a leader turn of
    `leader-s` seconds before the member can start it. This is the protocol
    prompts/team-lead.md asks for.
  - `board`: members pull the next task the moment they are free. No leader
    turn per task; this is what a board would buy.

  Task durations are log-normal around `mean-s`, so a few tasks run long, as
  real ones do. Prints raw makespans, averaged over seeded trials, so the
  conclusion can be re-derived rather than trusted.")

(defn- rng
  "A seeded generator of uniforms in (0,1): Park-Miller, mod 2^31-1, whose
  products stay inside a long (jolt promotes an overflowing one to a BigInt,
  and has no Random.nextGaussian)."
  [seed]
  (let [m 2147483647
        state (atom (inc (mod seed (dec m))))]
    (fn [] (/ (double (swap! state #(mod (* % 48271) m))) m))))

(defn- gaussian
  "A standard normal draw from two uniforms (Box-Muller)."
  [u]
  (* (Math/sqrt (* -2.0 (Math/log (u)))) (Math/cos (* 2.0 Math/PI (u)))))

(defn- durations
  "`n` task durations in seconds: log-normal, median `mean-s`, from `u`."
  [u n mean-s]
  (vec (repeatedly n #(* mean-s (Math/exp (* 0.8 (gaussian u)))))))

(defn upfront
  "Round-robin into the members' queues in one leader turn."
  [ds m leader-s]
  (+ leader-s
     (apply max (for [k (range m)] (reduce + (take-nth m (drop k ds)))))))

(defn- greedy
  "List scheduling: each task, in order, to the member free first, which
  starts it `gap` seconds later. Every member is free at `start`. Returns the
  makespan."
  [ds m gap start]
  (apply max (reduce (fn [free d]
                       (let [i (first (apply min-key second (map-indexed vector free)))]
                         (assoc free i (+ (free i) gap d))))
                     (vec (repeat m start)) ds)))

(defn wakeup
  "One per member in the first leader turn; then a leader turn of `leader-s`
  between an answer landing and that member's next task."
  [ds m leader-s]
  ;; the first tasks go out in the leader's first turn: no gap before them
  (let [[first-round later] (split-at m ds)
        free (mapv #(+ leader-s %) first-round)]
    (apply max (reduce (fn [free d]
                         (let [i (first (apply min-key second (map-indexed vector free)))]
                           (assoc free i (+ (free i) leader-s d))))
                       (into free (repeat (- m (count first-round)) leader-s)) later))))

(defn board
  "Members pull; the leader's one turn is writing the board."
  [ds m leader-s]
  (greedy ds m 0 leader-s))

(defn- mean [xs] (/ (reduce + xs) (count xs)))

(defn -main [& _]
  (let [trials 500]
    (println (format "%d trials each; task median 60 s (log-normal, sigma 0.8)" trials))
    (println (format "%-8s %-6s %-9s %10s %10s %10s %10s" "members" "tasks" "leader-s" "upfront" "wakeup" "board" "ideal"))
    (doseq [[m n] [[2 6] [3 12] [4 16] [4 40]]
            leader-s [5 15 30]]
      (let [u (rng 42)
            runs (doall (repeatedly trials #(durations u n 60.0)))
            f (fn [policy] (mean (map #(policy % m leader-s) runs)))
            ideal (mean (map #(max (/ (reduce + %) m) (apply max %)) runs))]
        (println (format "%-8d %-6d %-9d %10.0f %10.0f %10.0f %10.0f"
                         m n leader-s (f upfront) (f wakeup) (f board) ideal))))))
