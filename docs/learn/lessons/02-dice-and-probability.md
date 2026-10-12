# Lesson 2 — Dice and probability: what a d20 game computes

*A lesson from M15. Chapter [15 Rules](../15-rules.md) §2 states the formulas a d20 game runs
on; this lesson derives them from the definition of a die, puts numbers on how swingy each roll
is, and ends in the generator that turns 64 random bits into a fair die (`math.Rng`) and in how
the tests pin down probabilities without statistics. Read chapter 15 first; nothing here needs
more than fractions and a little algebra.*

## 1. A die is a random variable

A fair die with $S$ faces is a random variable $X$ that takes each value $1, \dots, S$ with
probability $1/S$. Two numbers summarise it:

$$E[X] = \sum_{k=1}^{S} k \cdot \frac{1}{S} = \frac{1}{S} \cdot \frac{S(S+1)}{2} = \frac{S+1}{2},
\qquad
\operatorname{Var}[X] = E[X^2] - E[X]^2 = \frac{(S+1)(2S+1)}{6} - \frac{(S+1)^2}{4} = \frac{S^2 - 1}{12}.$$

The second line uses $\sum k^2 = S(S+1)(2S+1)/6$ and a little algebra. Worked out:

| Die | Mean | Variance | Standard deviation |
|---|---|---|---|
| d4 | 2.5 | 1.25 | 1.12 |
| d6 | 3.5 | 2.92 | 1.71 |
| d8 | 4.5 | 5.25 | 2.29 |
| d20 | 10.5 | 33.25 | 5.77 |

The standard deviation is the number to feel: a d20 "typically" lands 5.8 away from its mean,
which is more than a quarter of its range. That is not a defect, it is the design: the d20 is
the die the game uses when it *wants* an outcome to be uncertain, and the small dice are what it
uses when it wants a quantity to be predictable. The rest of the lesson is about why those two
roles need two different shapes of distribution.

`Dice.average` in the code is the first formula, `count * (sides + 1) * 0.5f + bonus`; the
engine never needs the variance at run time, but you need it to read the next sections.

## 2. Sums of dice: convolution and the bell

`NdS` is the sum of $N$ independent copies of $X$. Expectations add always and variances add
for independent variables, so

$$E[NdS + B] = N \cdot \frac{S+1}{2} + B, \qquad \operatorname{Var}[NdS + B] = N \cdot \frac{S^2 - 1}{12}.$$

The *shape* of the sum is the **convolution** of the single-die distributions: the probability
of a total $t$ is the sum, over every way to split $t$ between the dice, of the product of their
probabilities. For 2d6, $P(T = t)$ counts the ordered pairs $(a, b)$ with $a + b = t$ out of 36:

```
t:    2     3     4     5     6     7     8     9    10    11    12
P: 1/36  2/36  3/36  4/36  5/36  6/36  5/36  4/36  3/36  2/36  1/36
```

a triangle, not a flat line: 7 is six times as likely as 2. Add a third die and the triangle
rounds into a bell; by 8d6 the sum is, for every practical purpose, a normal distribution with
mean 28 and standard deviation $\sqrt{8 \cdot 35/12} = 4.83$ (the central limit theorem, which
says exactly this: sums of many independent variables tend to the bell whatever the parts look
like). The consequences are what a designer uses:

- **A fireball is reliable.** 8d6 lands in 23–33 with probability 0.74 and at or below 20 with
  probability 0.06. The caster can plan on "about 28"; nobody can plan on a single d20.
- **Rolling twice is not doubling.** A confirmed critical with a ×2 weapon rolls the damage dice
  twice (`Rules.attack` loops `multiplier` times over `Dice.roll`). For Aldric's `1d8+4` both
  versions average 17 and both range over 10–24, but the sum of two rolls has variance
  $2 \cdot 5.25 = 10.5$ (σ 3.2) while doubling one roll has $4 \cdot 5.25 = 21$ (σ 4.6): a
  rolled-twice critical is a more dependable 17. That is the whole reason the code rolls instead
  of multiplying the result.
- **The shape decides what a +1 is worth.** Section 3 makes this precise.

The variance formula is why `Dice.times` multiplies the *count* and the bonus, never the total:
`times(1d8+4, 2)` is `2d8+8`, the distribution the rules mean.

## 3. The check: one die against a threshold

A check is $d20 + b \ge DC$ with a natural 20 always succeeding and a natural 1 always failing.
Let $n = DC - b$ be the face needed. The die is flat, so the probability is a count of faces:

$$P(\text{success}) =
\begin{cases}
19/20 & n \le 1 \quad \text{(every face but the 1)} \\
(21 - n)/20 & 2 \le n \le 20 \quad \text{(faces } n, \dots, 20\text{)} \\
1/20 & n \ge 21 \quad \text{(only the 20)}
\end{cases}$$

Tabulated, with $n$ across the top:

```
n:    ≤1   2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18   19   20   ≥21
P:   .95  .95  .90  .85  .80  .75  .70  .65  .60  .55  .50  .45  .40  .35  .30  .25  .20  .15  .10  .05  .05
```

Read the middle of the table as a function of $b$: **each point of bonus is worth exactly
5 percentage points, wherever you are**. That linearity is the property that makes a d20 game
easy to balance, and it is a property of the flat distribution alone. Compare a 3d6 system
(GURPS and its relatives), where the needed total $n$ sits on a bell: $P(3d6 \ge 10) = 0.625$
and $P(3d6 \ge 11) = 0.500$, so a point near the middle is worth 12.5 points of probability,
while $P(3d6 \ge 16) = 0.046$ and $P(3d6 \ge 17) = 0.019$, a point in the tail is worth 2.8.
In such a system a +1 matters enormously to an average character and barely at all to an
expert, which is a legitimate design but a different one. The engine's `Rules.check` is the
three-line version of the table:

```java
public boolean check(int modifier, int dc) {
    lastNatural = rng.d20();
    lastTotal = lastNatural + modifier;
    return lastNatural == 20 || (lastNatural != 1 && lastTotal >= dc);
}
```

The two clipped ends are not decoration. Without them a +100 bonus would never fail and a −100
would never succeed; with them the table is bounded in $[0.05, 0.95]$, which keeps every fight
in the game's reach and is what `RulesTest.naturalOneMissesAndNaturalTwentyHits` pins down
with seeds whose first roll is a 1 and a 20 (§8 says how it finds them).

**A check is a Bernoulli trial.** It has two outcomes with probabilities $p$ and $1 - p$. Trials
compose: the probability of at least one hit in $k$ independent swings at $p$ is $1 - (1-p)^k$,
so three swings at 0.70 land something with probability $1 - 0.3^3 = 0.973$, and the number of
swings until the first hit is geometric with mean $1/p$: 1.43 swings at 0.70, 5 at 0.20.

## 4. Critical hits: a conditional second trial

A critical needs the natural roll in the weapon's threat range *and* to hit, then a
**confirmation** roll that also hits. Three events, two of them on the same die:

$$P(\text{crit}) = P(\text{natural} \ge \text{threat} \;\wedge\; \text{hit}) \cdot P(\text{confirm}).$$

When the needed face $n$ is at or below the threat range, every threatening face also hits, so
the first factor is just the width of the range: $2/20$ for 19–20, $3/20$ for 18–20, $1/20$ for
a 20-only weapon. When $n$ is *inside* the range (you need a 20 and the sword threatens on
19–20), only the faces that also hit count — the 19 is a miss and a miss cannot threaten, which
is why `attack` tests `natural >= threat` only after `check` returned true. The confirmation is
an independent trial at the same $p$ (the same bonus against the same AC).

Expected damage per swing, with $D$ the mean damage and $m$ the multiplier:

$$E = p \cdot D + P(\text{threat} \wedge \text{hit}) \cdot p \cdot (m - 1) \cdot D.$$

Aldric, +10 against AC 17 ($n = 7$, $p = 0.70$), longsword `1d8+4` ($D = 8.5$, threat 19–20,
$m = 2$):

$$E = 0.70 \cdot 8.5 + 0.10 \cdot 0.70 \cdot 8.5 = 5.95 + 0.595 = 6.545.$$

The critical is 9 % of his output. It grows with the range and the multiplier — 18–20 gives
0.89, a ×3 greataxe (20 only) gives $0.05 \cdot 0.70 \cdot 2 \cdot 8.5 = 0.595$, the same as
the longsword's wider range — but it never rivals the first term. If you want a character to
hit harder, give them a bonus to hit; criticals are the part of the design that makes the
table exciting, not the part that wins.

**Iteratives.** A full attack at BAB 11 is `+11/+6/+1`. Against AC 17 the three swings need 6,
11 and 16, hit with 0.75, 0.50 and 0.25, and are worth 7.0, 4.7 and 2.3 expected damage (the
third attack's threat range still counts, it just hits less). A full attack is 14.0 against the
7.0 of a single swing after moving, which is the trade the action budget (chapter 15 §3.6)
exists to offer: standing still doubles the damage, walking costs the extra swings.

## 5. Saving throws: the same die, a different payoff

A save is `check(saveBonus, dc)`. What differs is what success buys. For *fireball*'s
"half on a successful save", with $p_s$ the save probability and $D$ the mean damage:

$$E = (1 - p_s) \cdot D + p_s \cdot \frac{D}{2} = D \left(1 - \frac{p_s}{2}\right).$$

Cassius sets DC 18 (10 + level 3 + Int modifier 5). Dara (Reflex +12) needs a 6, $p_s = 0.75$,
and expects $17.5 \cdot 0.625 = 10.9$ from `5d6`; a skeletal warrior (Reflex +4) needs 14,
$p_s = 0.35$, and expects $17.5 \cdot 0.825 = 14.4$. Note the lever: a point of save bonus is
worth $0.05 \cdot D/2$, half of what a point of AC is worth against a weapon of the same mean,
because a successful save only halves. "Negates" effects (`saveResult: negates`) use
$E = (1 - p_s) \cdot D$ and a point of bonus is worth the full $0.05 \cdot D$ there.

## 6. How long a fight takes

Expected damage per swing is an average, and a fight is a sequence of swings until hit points
reach zero: how many swings does it take, on average, to destroy a skeletal warrior (16 hp)
with `1d8+4`? The mean damage 8.5 suggests "about two hits", but a hit does 5–12 and the number
of hits to reach 16 is itself random. Exactly: let $H(r)$ be the expected hits still needed
with $r$ hit points left; $H(r) = 0$ for $r \le 0$ and otherwise

$$H(r) = 1 + \frac{1}{8} \sum_{f=1}^{8} H(r - f - 4),$$

which gives $H(16) = 2.33$ hits (not 16/8.5 = 1.88: damage past zero is wasted, so the mean
overstates). At $p = 0.70$ each hit costs $1/p$ swings, so $2.33 / 0.70 = 3.3$ swings, which is
3.3 rounds for a character with one attack and what the demo's logs show on average. This kind
of recursion (a **Markov chain** on the remaining hit points) is how to answer any "how many
rounds" question exactly; the Monte Carlo in §9 answers the same questions approximately and
much faster to write.

## 7. From 64 random bits to a fair d20

Everything above assumed a *fair* die. The engine's die is `Rng.d20()`, which is
`1 + nextInt(20)`, and `nextInt(bound)` has to turn uniform random bits into a uniform integer
in $[0, 20)$ without bias. The obvious `bits % 20` is biased: $2^{32}$ is not a multiple of 20
($2^{32} \bmod 20 = 16$), so the sixteen residues 0–15 occur one more time than 16–19 do. The
bias is one part in $2^{32}/20 \approx 2 \cdot 10^8$, invisible in any game, but a generator
that is *exactly* uniform costs the same, and the code uses it:

```java
public int nextInt(int bound) {
    long m = (nextLong() >>> 32) * bound;   // 32 random bits times the bound: 64 bits
    long low = m & 0xFFFFFFFFL;             // the low half decides the rejection
    if (low < bound) {
        long threshold = (0x1_0000_0000L - bound) % bound;   // = 2^32 mod bound
        while (low < threshold) {           // reject the few values that would be over-represented
            m = (nextLong() >>> 32) * bound;
            low = m & 0xFFFFFFFFL;
        }
    }
    return (int) (m >>> 32);                // the high half is the result
}
```

This is **Lemire's method**. The product $x \cdot \text{bound}$ of a 32-bit $x$ splits the
range $[0, 2^{32} \cdot \text{bound})$ into `bound` intervals of width $2^{32}$, and the high
32 bits say which interval $x$ fell into: that is a multiply instead of a division, and it is
uniform *except* that the intervals do not share the $2^{32} \bmod \text{bound}$ leftover
evenly. The low 32 bits tell whether $x$ is one of those leftover positions, and only those are
rolled again: for a d20 the threshold is 16, so a reroll happens with probability
$16 / 2^{32} = 3.7 \cdot 10^{-9}$. The `if (low < bound)` guard skips computing the modulo at all
in every other case. A d20 is therefore one 64-bit multiply, one compare and, once in a few
hundred million rolls, a second draw.

The bits themselves come from **xoshiro256\*\*** (`nextLong`): four `long`s of state, a few
shifts, xors and rotates, and a period of $2^{256} - 1$. The seed goes through SplitMix64 first
(`setSeed`) so that seeds 1, 2, 3 give unrelated states rather than states that differ in one
bit. Two properties matter to the game more than the statistical quality (which is excellent
for anything but cryptography):

- **Determinism.** The same seed gives the same rolls forever, on every machine:
  `RulesTest.aSeedReplaysTheSameFight` runs a 20-round fight twice and asserts the event
  streams are identical, and the editor's play mode (M23) will replay an encounter from a seed.
- **The state is the save.** Four `long`s (`saveState`) put a fight back exactly where it
  stopped, which M16 will write into the save file.

## 8. Testing probabilities without statistics

How do you test that "a natural 1 always misses"? Rolling until a 1 shows up is a test that
sometimes takes 50 rolls and sometimes 1; asserting on a run of 1000 rolls makes the test slow
and leaves a tiny chance of failing. The tests instead *choose the seed*:

```java
/** The first seed whose first d20 is {@code wanted}. */
private static long seedWhereFirstD20Is(int wanted) {
    for (long s = 1; ; s++) {
        if (new Rng(s).d20() == wanted) {
            return s;
        }
    }
}
```

With `seedWhereFirstD20Is(1)` the test knows the first roll and asserts the outcome exactly;
`seedWhereD20sAre(a, b)` does the same for an attack followed by its confirmation. The search
takes 20 seeds on average (the rolls are uniform, so each seed has a 1/20 chance), and the test
is deterministic. This is the general technique for testing anything random: fix the randomness
and test the logic.

When the question *is* statistical — is the die fair? — the test has to accept a tolerance, and
the tolerance comes from §1. The count of 1s in $N$ rolls is binomial with mean $N/20$ and
standard deviation $\sqrt{N \cdot 0.05 \cdot 0.95}$: in a million rolls that is 50,000 ± 218,
so a face count outside 49,000–51,000 (4.6 σ) is a bug, not luck. To *detect* a bias of one
percentage point (a face at 6 % instead of 5 %) at three standard deviations you need

$$N \ge \frac{z^2 \, p (1 - p)}{\varepsilon^2} = \frac{9 \cdot 0.0475}{0.0001} \approx 4300$$

rolls. The engine's own check (`GeometryAndUtilTest.rngIsDeterministicAndUniform`) rolls
60,000 d6 and asserts every face count within 500 of 10,000. Put §1 on it: the count of one
face is binomial with σ = $\sqrt{60000 \cdot (1/6)(5/6)} = 91$, so 500 is 5.5 σ, a bound a
fair die crosses (on any of its six faces) once in about four million runs, while a face biased by one percentage point
(expected count 10,600) crosses it in 86 % of runs. That is what "a tolerance from the
variance" means in a test: wide enough never to flake, narrow enough to catch the bug you are
guarding against. Determinism is tested the exact way: two generators with the same seed must
agree on 100 `nextLong` calls, and a saved state must continue identically.

## 9. Things to run

- `./gradlew :rules:test --tests 'dev.jeddy.rules.RulesTest'`:
  `anAttackRollIsD20PlusBonusAgainstArmorClass`, `naturalOneMissesAndNaturalTwentyHits`,
  `aConfirmedThreatMultipliesTheDamage` and `aSeedReplaysTheSameFight` are §3, §4 and §7 as
  code; `./gradlew :math:test` runs the die's uniformity check of §8.
- `./gradlew :iso-demo:run --args="--turn-based --echo-combat"` prints every roll with its
  natural and total; count hits over a few rounds and compare with the table in §3 for the
  attacker's bonus and the target's AC (the F3 overlay shows both).
- A Monte Carlo for §6, as a scratch test in `modules/rules/src/test`:

  ```java
  Rng rng = new Rng(1);
  long swings = 0;
  for (int fight = 0; fight < 100_000; fight++) {
      int hp = 16;
      while (hp > 0) {
          swings++;
          int natural = rng.d20();
          if (natural == 20 || (natural != 1 && natural + 10 >= 17)) {
              hp -= rng.roll(1, 8, 4);
          }
      }
  }
  System.out.println(swings / 100_000.0); // about 3.33
  ```

## 10. Exercises

1. Derive $\operatorname{Var}[X] = (S^2 - 1)/12$ from $E[X^2] = (S+1)(2S+1)/6$ and check d6
   against 35/12. *Hint: $E[X^2] - E[X]^2$; put both over 12.*
2. Compute the 3d6 distribution by convolving the 2d6 triangle with a d6, and verify
   $P(3d6 \ge 16) = 10/216$. *Hint: $P(T_3 = t) = \sum_f P(T_2 = t - f) / 6$.*
3. The demo's skeletal stalker attacks twice (`+4/+4`, two-weapon fighting, `1d4+1`, 19–20
   ×2) against Aldric's AC 20. Compute its expected damage per full attack and compare with a
   skeletal warrior's single `+4`, `1d6+2`, 19–20 ×2. *Hint: §4's formula twice; $n = 16$ for
   both.*
4. Change `Rules.attack` to multiply one damage roll instead of rolling `multiplier` times and
   find the test that fails. Then compute the variance of both versions for a ×3 greataxe
   (`1d12`). *Hint: §2; the ranges are equal, the spreads are not.*
5. Replace `nextInt(bound)` with `(int) ((nextLong() >>> 32) % bound)` and write a test that
   detects the bias. *Hint: it cannot in any reasonable time for `bound = 20` — compute how many
   rolls §8's formula asks for when $\varepsilon = 20/2^{32}$. Try `bound = 3_000_000_000L`
   territory instead: with a 32-bit source the leftover is large.*
6. Write the Markov chain of §6 for Dara's two dagger attacks (`+8/+8`, both at full bonus
   because the second comes from a feat, `1d4-1` each, 19–20 ×2) against a skeletal warrior
   (AC 17, 16 hp) and check it against a Monte Carlo. *Hint: the state is the remaining hp; one
   round is two Bernoulli trials, and `Rules.attack` clamps a hit to at least 1 damage.*

---

*Code this lesson quotes: `math.Rng`, `math.Dice`, `rules.Rules.check`, `rules.Rules.attack`,
`RulesTest`. Decisions: D96 (dice in an `int`), D99 (the fight is a stream of events, replayed
from a seed) in [DESIGN §17](../../DESIGN.md).*
