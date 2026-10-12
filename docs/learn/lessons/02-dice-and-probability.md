# Lesson 2 — Build the dice: probability from scratch, a random number generator and the d20 rules

*The rules module plays a tabletop game: a character swings a sword, a twenty-sided die decides
whether it hits, more dice decide how hard. This lesson builds that from the ground up. Part A
teaches probability from counting, with no prior math, and derives every formula the rules use.
Part B builds the engine's random number generator, `math.Rng`, line by line: how 256 bits of
state become a stream of fair numbers, and why the obvious `random % 20` is subtly wrong. Part C
builds `math.Dice`, which packs "2d6+3" into one `int`, and the two methods of `rules.Rules` that
roll for an attack. Part D shows how to test code whose whole job is to be random.*

**Before you start.** You need [Lesson 0](00-foundations.md) §2 (sums, powers, `mod`), §4–5 (bits
and two's complement) and §6 (shifts, masks and packing). Nothing about probability is assumed.
Chapter [15 Rules](../15-rules.md) describes the whole rules module; this lesson goes deeper into
the parts that roll dice.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The generator | [`math.Rng`](../../../modules/math/src/main/java/dev/jeddy/math/Rng.java) | 64 random bits per call from four `long`s of state; fair integers below any bound; dice |
| Dice expressions | [`math.Dice`](../../../modules/math/src/main/java/dev/jeddy/math/Dice.java) | `NdS+B` packed into an `int`: pack, unpack, parse, roll, average |
| The check and the attack | [`rules.Rules`](../../../modules/rules/src/main/java/dev/jeddy/rules/Rules.java) `check`, `attack` | d20 + bonus against a target number; threats, confirmation and critical damage |
| The tests | `RulesTest`, `GeometryAndUtilTest` | exact tests by choosing seeds; statistical tests with tolerances derived from the math |

**Contents**

- Part A — Probability from zero
  1. [Outcomes and probability](#1-outcomes-and-probability)
  2. [Combining events: not, and, or](#2-combining-events-not-and-or)
  3. [A die as a random variable: the mean](#3-a-die-as-a-random-variable-the-mean)
  4. [How spread out: variance and standard deviation](#4-how-spread-out-variance-and-standard-deviation)
  5. [Sums of dice: from a flat line to a bell](#5-sums-of-dice-from-a-flat-line-to-a-bell)
  6. [The d20 check: a threshold on a flat die](#6-the-d20-check-a-threshold-on-a-flat-die)
  7. [Trying again: at least once, and how many tries](#7-trying-again-at-least-once-and-how-many-tries)
  8. [Critical hits: conditional probability and expected damage](#8-critical-hits-conditional-probability-and-expected-damage)
  9. [Saving throws: the same die, a different payoff](#9-saving-throws-the-same-die-a-different-payoff)
  10. [How long a fight takes: a recursion on hit points](#10-how-long-a-fight-takes-a-recursion-on-hit-points)
- Part B — Building the random number generator
  11. [What a pseudo-random generator is](#11-what-a-pseudo-random-generator-is)
  12. [Step 1: state and seed](#12-step-1-state-and-seed)
  13. [Step 2: 64 random bits — xoshiro256**](#13-step-2-64-random-bits--xoshiro256)
  14. [Step 3: a fair number below a bound — Lemire's method](#14-step-3-a-fair-number-below-a-bound--lemires-method)
  15. [Step 4: dice, floats and the rest of the API](#15-step-4-dice-floats-and-the-rest-of-the-api)
- Part C — Dice in an `int` and the rules that roll them
  16. [Step 5: `Dice` — NdS+B in 32 bits](#16-step-5-dice--ndsb-in-32-bits)
  17. [Step 6: `Rules.check`](#17-step-6-rulescheck)
  18. [Step 7: `Rules.attack`](#18-step-7-rulesattack)
- Part D — Testing randomness
  19. [Fix the seed, test the logic](#19-fix-the-seed-test-the-logic)
  20. [When the question is statistical](#20-when-the-question-is-statistical)
  21. [Things to run](#21-things-to-run)
  22. [Exercises](#22-exercises)
  23. [Glossary](#23-glossary)

---

# Part A — Probability from zero

## 1. Outcomes and probability

Roll an ordinary six-sided die. Six things can happen: 1, 2, 3, 4, 5 or 6. Each is an
**outcome**. A fair die makes them **equally likely**: no face is favoured.

An **event** is any collection of outcomes you care about: "an even number" is the outcomes
{2, 4, 6}; "at least 5" is {5, 6}. When all outcomes are equally likely, the **probability** of an
event is a count:

$$P(\text{event}) = \frac{\text{number of outcomes in the event}}{\text{number of outcomes in total}}$$

So $P(\text{even}) = 3/6 = 0.5$ and $P(\text{at least 5}) = 2/6 \approx 0.333$. A probability is
always between 0 (impossible: $P(7) = 0/6$) and 1 (certain: $P(\text{1 to 6}) = 6/6$). Written as a
percentage, 0.333 is 33.3 %.

**What it means.** A probability of 1/3 does not mean "every third roll". It means that over many
rolls, the *fraction* of rolls in the event gets closer and closer to 1/3. Roll 6 times and you
might see no 5 or 6 at all; roll 6,000 times and you will see very close to 2,000.

Games use dice named by their faces: a **d4**, **d6**, **d8**, **d10**, **d12** and **d20** have 4,
6, 8, 10, 12 and 20 faces numbered from 1. On a d20, $P(\text{at least 5}) = 16/20 = 0.8$: the faces
5 to 20 are 16 of the 20.

## 2. Combining events: not, and, or

Three rules, each checked by counting.

### 2.1 Not

The outcomes *not* in an event are everything else, so

$$P(\text{not } A) = 1 - P(A).$$

$P(\text{not a 1 on a d20}) = 1 - 1/20 = 19/20$. This "complement" rule is often the easy way round:
counting what you do *not* want can be simpler than counting what you do.

### 2.2 And, for independent events

Roll two d6, one red and one blue. Each pair (red, blue) is an outcome, and there are $6 \times 6 =
36$ pairs, all equally likely. "Both show 6" is one pair: $P = 1/36$. Notice that $1/36 = 1/6 \times
1/6$: the probability of the red 6 times the probability of the blue 6.

That works because the dice are **independent**: what one shows does not change the chances of the
other. For independent events,

$$P(A \text{ and } B) = P(A) \times P(B).$$

Why, by counting: if $A$ is $a$ outcomes out of $n$ for the first die and $B$ is $b$ out of $m$ for
the second, the pairs where both happen are $a \times b$ out of $n \times m$, which is $\frac{a}{n}
\times \frac{b}{m}$.

Every roll of the engine's generator is independent of the others (Part B is about making that
true), so the rules multiply probabilities of separate rolls all the time.

### 2.3 Or

If two events cannot both happen (they are **mutually exclusive**), their outcomes do not overlap
and the counts simply add: $P(\text{a 1 or a 20 on one d20}) = 1/20 + 1/20 = 2/20$.

If they *can* both happen, adding counts the overlap twice, so subtract it once:

$$P(A \text{ or } B) = P(A) + P(B) - P(A \text{ and } B).$$

Example: two d6, "at least one 6". $P(\text{red 6}) + P(\text{blue 6}) - P(\text{both}) = 6/36 +
6/36 - 1/36 = 11/36$. Check by counting: the pairs with a 6 are the six with red 6 and the six with
blue 6, but (6, 6) is in both lists, so 11. Or by the complement: $1 - P(\text{no 6 on either}) = 1 -
(5/6)^2 = 1 - 25/36 = 11/36$. Three methods, one answer.

<details><summary>Check: on a d20, what are P(18 or higher), P(not 18 or higher), and for two d20s, P(both 18 or higher)?</summary>

3/20 = 0.15 (faces 18, 19, 20); 17/20 = 0.85; $0.15 \times 0.15 = 0.0225$.
</details>

## 3. A die as a random variable: the mean

### 3.1 Random variables

A **random variable** is a number whose value depends on an outcome: "what the die shows", "the
total of two dice", "the damage of a sword hit". It is written with a capital letter, $X$. Its
**distribution** lists each value it can take with its probability. For a d6:

```
value x:      1     2     3     4     5     6
P(X = x):   1/6   1/6   1/6   1/6   1/6   1/6
```

### 3.2 The mean (expected value)

If you rolled a d6 thousands of times and averaged the results, what would you get? Each face turns
up about 1/6 of the time, so the average is

$$1 \cdot \tfrac16 + 2 \cdot \tfrac16 + 3 \cdot \tfrac16 + 4 \cdot \tfrac16 + 5 \cdot \tfrac16 + 6 \cdot \tfrac16
= \frac{21}{6} = 3.5.$$

This is the **mean** or **expected value**, written $E[X]$: each value times its probability,
summed (L00 §2.2):

$$E[X] = \sum_{x} x \cdot P(X = x).$$

The mean need not be a value the die can show — nobody rolls a 3.5 — it is the long-run average.

**For any fair die with $S$ faces**, each face has probability $1/S$, so

$$E[X] = \sum_{k=1}^{S} k \cdot \frac{1}{S} = \frac{1}{S} \sum_{k=1}^{S} k = \frac{1}{S} \cdot \frac{S(S+1)}{2} = \frac{S+1}{2},$$

using the sum of the first $S$ numbers from L00 §2.2. A d6 averages $7/2 = 3.5$, a d8 4.5, a d20
10.5.

### 3.3 Means add

Two facts make means easy to work with:

- **Adding a constant adds it to the mean:** $E[X + b] = E[X] + b$. A sword doing "1d8 + 4"
  averages $4.5 + 4 = 8.5$.
- **The mean of a sum is the sum of the means:** $E[X + Y] = E[X] + E[Y]$, always, even if $X$ and
  $Y$ are not independent. Two d6 average $3.5 + 3.5 = 7$.

So $N$ dice of $S$ faces plus a bonus $B$, written **$N$d$S$+$B$**, average

$$E[N\text{d}S + B] = N \cdot \frac{S+1}{2} + B.$$

That formula is the whole of `Dice.average` (§16): `count * (sides + 1) * 0.5f + bonus`. Eight d6
average $8 \times 3.5 = 28$.

## 4. How spread out: variance and standard deviation

Two damage rolls can have the same mean and feel completely different: "always 7" versus
"anything from 2 to 12". The second number that describes a random variable is how far it
typically lands from its mean.

### 4.1 Variance, worked for a d6

The **variance** is the average of the *squared* distance from the mean:

$$\operatorname{Var}[X] = E\big[(X - E[X])^2\big].$$

For a d6 (mean 3.5), as a table:

| Face $x$ | Distance $x - 3.5$ | Squared |
|---|---|---|
| 1 | −2.5 | 6.25 |
| 2 | −1.5 | 2.25 |
| 3 | −0.5 | 0.25 |
| 4 | 0.5 | 0.25 |
| 5 | 1.5 | 2.25 |
| 6 | 2.5 | 6.25 |
| | | sum 17.5 |

Each face has probability 1/6, so the average of the last column is $17.5 / 6 = 35/12 \approx 2.917$.

Why square? A plain average of the distances would be 0 (the negatives cancel the positives).
Squaring makes every distance count positively, counts big misses more than small ones, and — the
real reason — makes variances of independent dice *add* (§5.2).

The **standard deviation**, $\sigma$ (the Greek letter sigma), is the square root of the variance,
which brings it back to the units of the die: $\sigma = \sqrt{35/12} \approx 1.71$. Read it as "a
d6 typically lands about 1.7 away from 3.5".

### 4.2 A shortcut formula

Expanding the square gives a formula that is easier to compute. With $\mu = E[X]$:

$$E[(X - \mu)^2] = E[X^2 - 2\mu X + \mu^2] = E[X^2] - 2\mu E[X] + \mu^2 = E[X^2] - 2\mu^2 + \mu^2 = E[X^2] - \mu^2.$$

(Each step uses "means add" and "constants come out" from §3.3.) So

$$\operatorname{Var}[X] = E[X^2] - E[X]^2.$$

Check with a d6: $E[X^2] = (1 + 4 + 9 + 16 + 25 + 36)/6 = 91/6$, and $91/6 - 3.5^2 = 91/6 - 49/4 =
182/12 - 147/12 = 35/12$. ✓

### 4.3 The variance of any fair die

We need $E[X^2] = \frac1S \sum_{k=1}^{S} k^2$. The sum of the first $S$ squares has a known formula:

$$\sum_{k=1}^{S} k^2 = \frac{S(S+1)(2S+1)}{6}.$$

(Check $S = 3$: $1 + 4 + 9 = 14$ and $3 \cdot 4 \cdot 7 / 6 = 14$. ✓ Exercise 1 asks you to check it
for more values.) So $E[X^2] = \frac{(S+1)(2S+1)}{6}$, and

$$\operatorname{Var}[X] = \frac{(S+1)(2S+1)}{6} - \frac{(S+1)^2}{4}.$$

Put both fractions over 12 and take out the common factor $(S + 1)$:

$$= \frac{2(S+1)(2S+1) - 3(S+1)^2}{12} = \frac{(S+1)\,\big[2(2S+1) - 3(S+1)\big]}{12} = \frac{(S+1)(4S + 2 - 3S - 3)}{12} = \frac{(S+1)(S-1)}{12} = \frac{S^2 - 1}{12}.$$

For $S = 6$: $35/12$. ✓ The table every later section uses:

| Die | Mean $\frac{S+1}{2}$ | Variance $\frac{S^2-1}{12}$ | Standard deviation |
|---|---|---|---|
| d4 | 2.5 | 1.25 | 1.12 |
| d6 | 3.5 | 2.92 | 1.71 |
| d8 | 4.5 | 5.25 | 2.29 |
| d10 | 5.5 | 8.25 | 2.87 |
| d12 | 6.5 | 11.92 | 3.45 |
| d20 | 10.5 | 33.25 | 5.77 |

The number to feel is the d20's: it typically lands almost 6 away from its mean, more than a
quarter of its whole range. That is not a flaw. The d20 is the die the game rolls when it *wants*
the outcome to be uncertain (did the sword hit?), and the small dice are what it rolls when it wants
a quantity to be fairly predictable (how hard?). Those two jobs need differently shaped
distributions, which is what the next section is about.

## 5. Sums of dice: from a flat line to a bell

### 5.1 Two dice, by counting

The total of 2d6 can be 2 to 12, but not with equal chances. Write out all 36 pairs as a grid, the
red die down the side and the blue die across the top, each cell holding the total:

```
        blue: 1   2   3   4   5   6
red 1:        2   3   4   5   6   7
red 2:        3   4   5   6   7   8
red 3:        4   5   6   7   8   9
red 4:        5   6   7   8   9  10
red 5:        6   7   8   9  10  11
red 6:        7   8   9  10  11  12
```

Count each total:

```
total t:    2     3     4     5     6     7     8     9    10    11    12
P(T = t): 1/36  2/36  3/36  4/36  5/36  6/36  5/36  4/36  3/36  2/36  1/36
```

A triangle: 7 is six times as likely as 2, because six cells (the diagonal from the top right to
the bottom left) make 7 and one makes 2.
One die is flat; two dice already favour the middle.

### 5.2 Means and variances add

The mean of the total is $3.5 + 3.5 = 7$ (§3.3), the peak of the triangle. For **independent**
variables, variances add too:

$$\operatorname{Var}[X + Y] = \operatorname{Var}[X] + \operatorname{Var}[Y].$$

Why: expand $E[(X - \mu_X + Y - \mu_Y)^2]$ into $E[(X - \mu_X)^2] + E[(Y - \mu_Y)^2] + 2E[(X -
\mu_X)(Y - \mu_Y)]$. The last term is zero when $X$ and $Y$ are independent: for each value of $X$,
the deviations of $Y$ average to zero. So for $N$d$S$+$B$:

$$\operatorname{Var}[N\text{d}S + B] = N \cdot \frac{S^2 - 1}{12}, \qquad \sigma = \sqrt{N \cdot \frac{S^2 - 1}{12}}.$$

(The bonus moves the distribution without spreading it.) Notice the square root: $N$ dice have $N$
times the variance but only $\sqrt{N}$ times the spread, while the *range* grows $N$ times. Relative
to its range, a sum of many dice is narrow. That is the bell.

### 5.3 Three dice: convolution

To get the distribution of 3d6, take each total $u$ of the first two dice and each face $f$ of the
third: the probability of a total $t$ is the sum over the third die's faces of "the first two made
$t - f$":

$$P(T_3 = t) = \sum_{f=1}^{6} P(T_2 = t - f) \cdot \frac16.$$

This sum is called a **convolution**. Done for every $t$, as counts out of $6^3 = 216$:

```
total t:  3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18
count:    1   3   6  10  15  21  25  27  27  25  21  15  10   6   3   1    (sum 216)
```

The triangle has rounded into a bell. Add more dice and it keeps getting smoother, and closer to
the famous **normal distribution**, the "bell curve". That is the **central limit theorem**: the sum
of many independent random variables is close to normal, whatever each one looks like on its own.

### 5.4 What a designer gets from the bell

- **A fireball is reliable.** 8d6 (the classic fireball of an 8th-level caster) has mean 28 and
  $\sigma = \sqrt{8 \cdot 35/12} \approx 4.83$. Counting exactly (by convolving eight times), it lands
  between 23 and 33 with probability 0.740, and at 20 or less with probability only 0.061. The
  caster can plan on "about 28". Nobody can plan on a single d20.
- **Rolling twice is not doubling.** A critical hit (§8) with a ×2 weapon rolls the damage *twice*
  and adds; it does not roll once and double. For Aldric's sword, `1d8+4`, both ways average 17
  and range from 10 to 24, but:
  - rolled twice: $\operatorname{Var} = 2 \times 5.25 = 10.5$, $\sigma \approx 3.24$;
  - doubled: $\operatorname{Var}[2X] = 4 \operatorname{Var}[X] = 21$, $\sigma \approx 4.58$
    (doubling a number doubles every distance from the mean, so it multiplies the squared
    distances by 4).

  Rolled twice, a critical is a *more dependable* 17. That is why the engine rolls instead of
  multiplying (§18), and why `Dice.times`, which turns `1d8+4` into the expression for "twice
  that", multiplies the **count and the bonus** (`2d8+8`), never the total.
- **The shape decides what +1 is worth.** That is the next section.

## 6. The d20 check: a threshold on a flat die

### 6.1 The rule

Almost every uncertain action in a d20 game is a **check**: roll a d20, add a **bonus** $b$ (how
good the character is at this), and succeed if the total reaches a **difficulty class** $DC$
(how hard the task is): $d20 + b \ge DC$. An attack is a check against the target's **armor class**
(AC); a saving throw is a check against the spell's DC. Two exceptions: a **natural 20** (the die
itself shows 20) always succeeds and a **natural 1** always fails. (The tabletop game this engine
follows applies that rule to attacks and saving throws only; the engine's `check` applies it to
every check, which keeps one rule and one table.)

### 6.2 The probability

Let $n = DC - b$: the face the die must show. Faces $n$ through 20 succeed, and there are $21 - n$ of
them (for $n = 15$: 15, 16, 17, 18, 19, 20 — six faces, and $21 - 15 = 6$). With the two exceptions:

$$P(\text{success}) = \begin{cases}
19/20 & \text{if } n \le 1 \quad \text{(every face but the natural 1)} \\
(21 - n)/20 & \text{if } 2 \le n \le 20 \\
1/20 & \text{if } n \ge 21 \quad \text{(only the natural 20)}
\end{cases}$$

Tabulated:

```
n:   ≤1   2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18   19   20  ≥21
P:  .95  .95  .90  .85  .80  .75  .70  .65  .60  .55  .50  .45  .40  .35  .30  .25  .20  .15  .10  .05  .05
```

**Each point of bonus is worth exactly 5 percentage points**, anywhere in the middle of the table.
That straight line is a property of the flat d20 alone, and it is what makes the game easy to
balance: a +2 sword is a 10-point improvement for a beginner and for a veteran alike. The two
clipped ends keep every check possible and every check fallible: no bonus is so large that it
cannot fail (5 % remains) and no penalty so large that it cannot succeed.

### 6.3 Compare a bell

Some games roll 3d6 instead (GURPS, for example). There the needed total sits on the bell of §5.3,
and a point is worth very different amounts in different places. From the 3d6 counts, $P(3d6 \ge
10) = 135/216 = 0.625$ and $P(3d6 \ge 11) = 108/216 = 0.500$: one point in the middle is worth 12.5
points of probability. In the tail, $P(3d6 \ge 16) = 10/216 = 0.046$ and $P(3d6 \ge 17) = 4/216 =
0.019$: one point is worth 2.8. In such a game a +1 matters enormously to an average character and
barely at all to an expert. That is a legitimate design, but a different one; the d20's is the
straight line.

## 7. Trying again: at least once, and how many tries

A check is a **Bernoulli trial**: two outcomes, success with probability $p$ and failure with
$1 - p$. Two questions come up constantly.

**At least one success in $k$ independent tries.** Use the complement (§2.1): the only way to have
none is to fail every time, which has probability $(1 - p)^k$ (§2.2). So

$$P(\text{at least one}) = 1 - (1 - p)^k.$$

Three swings at $p = 0.70$: $1 - 0.3^3 = 1 - 0.027 = 0.973$.

**How many tries until the first success, on average?** Call the answer $E$. The first try succeeds
with probability $p$ (one try, done) or fails with probability $1 - p$, after which you are back
where you started having spent one try:

$$E = p \cdot 1 + (1 - p)(1 + E).$$

Solve for $E$: $E = p + 1 - p + E - pE$, so $0 = 1 - pE$, so $E = 1/p$. At $p = 0.70$ the first hit
takes $1/0.7 \approx 1.43$ swings on average; at $p = 0.20$, five. This trick — write the answer in
terms of itself after one step, then solve — is used again in §10.

## 8. Critical hits: conditional probability and expected damage

### 8.1 The rule

Each weapon has a **threat range**: a longsword threatens on a natural 19 or 20, a greataxe only on
20. When an attack *hits* and its natural roll is in the threat range, the attacker rolls a second
d20, the **confirmation**, with the same bonus against the same AC. If that also hits, the attack is
a **critical hit** and the damage is rolled **multiplier** times (×2 for a longsword, ×3 for a
greataxe) and added up.

### 8.2 Conditional probability

"The probability the confirmation succeeds, *given* that the first roll threatened" is a
**conditional probability**, written $P(A \mid B)$, "A given B". It is the probability of $A$ when
you only look at the outcomes where $B$ happened:

$$P(A \mid B) = \frac{P(A \text{ and } B)}{P(B)}.$$

Turned around, $P(A \text{ and } B) = P(B) \cdot P(A \mid B)$: "first $B$ must happen, then $A$ must
happen given $B$". For the critical: the first roll must threaten and hit, then the confirmation must
hit. The confirmation is a fresh, independent roll, so its probability given the threat is just its
own: $p$, the same as any attack's.

### 8.3 Counting the threatening faces

Which first rolls both hit and threaten? Count faces again. Let $n$ be the face needed to hit (§6.2)
and $t$ the lowest threatening face (19 for a longsword):

- If $n \le t$, every threatening face hits: the count is the width of the range, $21 - t$ faces.
  For 19–20, $q_1 = 2/20 = 0.10$.
- If $n > t$, only the threatening faces at or above $n$ hit (a natural 20 always does). With a
  longsword needing 20 to hit, only the 20 counts: the natural 19 *misses*, and a miss cannot
  threaten. That is why the code (§18) tests the threat only after the hit.

So $P(\text{critical}) = q_1 \cdot p$.

### 8.4 Expected damage per swing

Let $D$ be the mean damage of a normal hit and $m$ the multiplier. A critical rolls the damage $m$
times, so it averages $m \cdot D$ (§3.3). The three possible results of a swing are mutually
exclusive:

| Result | Probability | Mean damage |
|---|---|---|
| miss | $1 - p$ | 0 |
| normal hit | $p - q_1 p$ | $D$ |
| critical | $q_1 p$ | $mD$ |

The expected damage is each mean times its probability, summed (§3.2):

$$E = (p - q_1 p) D + q_1 p \cdot m D = p D + q_1 p (m - 1) D.$$

The first term is "every hit does $D$"; the second is "criticals add $(m-1)$ more of it".

**Worked example: Aldric against a skeletal warrior.** In the demo, Aldric attacks at +10 with a
longsword (`1d8+4`, $D = 8.5$, threat 19–20, ×2) and the warrior has AC 17. He needs $n = 17 - 10 =
7$, so $p = (21 - 7)/20 = 0.70$, and $n \le 19$ so $q_1 = 0.10$:

$$E = 0.70 \cdot 8.5 + 0.10 \cdot 0.70 \cdot 1 \cdot 8.5 = 5.95 + 0.595 = 6.545.$$

Criticals are 9 % of his damage. A wider range (18–20, $q_1 = 0.15$) makes that term 0.89; a ×3
greataxe that threatens only on 20 gives $0.05 \cdot 0.70 \cdot 2 \cdot 8.5 = 0.595$, the same as the
longsword's. Criticals never rival the first term. To make a character hit harder, give them a
bonus to hit; criticals are the excitement, not the engine of damage.

**Several attacks.** A character with a base attack bonus of 11 (a high level, not in the demo)
attacks three times in a full round, at +11, +6 and +1 (each extra attack at −5). Against AC 17 the
three need 6, 11 and 16, so they hit with 0.75, 0.50 and 0.25, and with the same longsword they are
worth about 7.0, 4.7 and 2.3: 14.0 per full round against 7.0 for one attack after moving. That
trade (stand still and swing more, or move and swing once) is what the action rules of chapter 15
are built around.

## 9. Saving throws: the same die, a different payoff

A **saving throw** is a check the *target* makes against an effect's DC: $d20 + \text{save bonus}
\ge DC$, the same function as an attack (§17 shows it is literally the same code). What changes is
what success buys. A *fireball* does half damage on a successful save. With $p_s$ the chance to
save and $D$ the mean damage:

$$E = (1 - p_s) \cdot D + p_s \cdot \frac{D}{2} = D\left(1 - \frac{p_s}{2}\right).$$

In the demo, Cassius casts *fireball* (`5d6`, $D = 17.5$) with DC 18: 10, plus the spell's level 3,
plus his Intelligence modifier +5 (Intelligence 20). Dara (Reflex +12) needs a 6 to save, $p_s =
0.75$, and by the formula expects $17.5 \times (1 - 0.375) \approx 10.9$. A skeletal warrior (Reflex
+4) needs 14, $p_s = 0.35$, and expects $17.5 \times 0.825 \approx 14.4$.

One correction from the code: `Rules` halves with integer division (`amount /= 2`), which rounds
down, so a halved 5d6 averages 8.5 rather than 8.75 (an odd total loses its half point, and half of
all 5d6 totals are odd). With that, Dara expects $0.25 \times 17.5 + 0.75 \times 8.5 = 10.75$ and the
warrior $0.65 \times 17.5 + 0.35 \times 8.5 = 14.35$. The formula is the model; the code is the
truth, and small details like rounding are where they differ.

Notice what one point of save bonus is worth: it raises $p_s$ by 0.05, which lowers $E$ by $0.05
\cdot D / 2$ — half of what a point of armor class is worth against an attack of the same mean,
because a successful save only halves. For an effect that is negated entirely by a save, $E = (1 -
p_s) D$ and a point of bonus is worth the full $0.05 D$.

## 10. How long a fight takes: a recursion on hit points

### 10.1 Why the mean is not enough

A skeletal warrior has 16 hit points. Aldric's hits do 8.5 on average, so "about two hits"? Not
quite: a hit does anywhere from 5 to 12, and damage beyond zero is wasted (a 12 on a skeleton with 3
hit points left still only finishes it). The mean number of hits is *more* than $16 / 8.5 \approx
1.88$. To get it exactly, use §7's trick.

### 10.2 The recursion

Let $H(r)$ be the expected number of hits still needed when the skeleton has $r$ hit points left.
If $r \le 0$ it is already destroyed: $H(r) = 0$. Otherwise, the next hit is one hit, and it does
$f + 4$ damage where $f$ is the d8 face, each face with probability 1/8, after which $H(r - f - 4)$
more are needed:

$$H(r) = 1 + \frac{1}{8} \sum_{f=1}^{8} H(r - f - 4).$$

Every term on the right has a smaller $r$, so you can fill in a table from $r = 1$ upwards:

| $r$ | 1–5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| $H(r)$ | 1 | 1.125 | 1.25 | 1.375 | 1.5 | 1.625 | 1.766 | 1.922 | 2.094 | 2.156 | 2.234 | **2.330** |

For $r \le 5$ any hit (at least 5) finishes it: one hit. For $r = 6$, only a d8 face of 1 (5 damage)
leaves 1 hit point, which needs one more hit: $H(6) = 1 + \frac18 \cdot H(1) = 1.125$. And so on.

So the warrior takes **2.33 hits** on average, and since each hit takes $1/p = 1/0.7$ swings (§7),
**3.33 swings** — ignoring criticals. Including them (a 7 % chance per swing of doing double), the
same recursion over the swing's full damage distribution gives 3.14 swings.

This kind of model — a **state** (hit points left), a probability for each next state, and an
equation that says "the answer from here is one step plus the answer from wherever the step lands"
— is a **Markov chain**. It answers any "how many rounds" question exactly. A **Monte Carlo
simulation** (play the fight many times with the random generator and average) answers the same
questions approximately, and is much quicker to write; §21 has one. Getting the same number from
both is a good check of each.

---

# Part B — Building the random number generator

## 11. What a pseudo-random generator is

A computer cannot roll a die. What it can do is run a **pseudo-random number generator** (PRNG): a
small amount of **state** (some numbers) and a **step function** that scrambles the state into the
next state and produces an output. Start it from the same state and it produces exactly the same
sequence, forever; the sequence merely *looks* random — it passes statistical tests that real
randomness would pass. The starting state is derived from a **seed**, a number you choose. The
**period** is how many outputs it makes before the state repeats and the sequence starts over.

For a game, "looks random and repeats exactly from a seed" is not a compromise but the main
feature. The engine's requirements:

1. **Determinism.** The same seed gives the same rolls on every machine and every run. A fight can
   be replayed from its seed, and tests can choose seeds that produce the rolls they need (§19).
2. **The state is small and readable.** A save game stores the generator's state and a load puts
   it back, so the next roll after loading is the roll that would have come (chapter 16).
3. **Fast, with no allocation and no locks.** A fight rolls thousands of dice per second on the game
   thread. Each generator belongs to one thread, so it needs no synchronization.
4. **Independent streams.** Combat, loot and AI each get their own generator, so adding one extra
   loot roll does not shift every later combat roll.

### 11.1 Why not the JDK's generators

| Candidate | Why not |
|---|---|
| `java.util.Random` | A 48-bit linear congruential generator: small state, a short period ($2^{48}$), weak low bits. Its methods are thread-safe through an atomic compare-and-set on every call, a cost a single-thread owner does not need. And its state cannot be read and restored except by serializing the object |
| `ThreadLocalRandom` | Cannot be seeded: no determinism |
| `SplittableRandom` and the Java 17 `RandomGenerator` algorithms | Good quality, but their state cannot be read out and put back, which requirement 2 needs |
| `SecureRandom` | Slow and deliberately unpredictable: the opposite of requirement 1 |

So the engine writes its own, about 200 lines: **xoshiro256\*\*** for the bits, **SplitMix64** to
turn a seed into a state, and **Lemire's method** for fair dice. All three are published, studied
algorithms (by Blackman and Vigna, Steele et al., and Lemire); the engine's job is to implement them
exactly. DESIGN §8.3 names the choice.

## 12. Step 1: state and seed

Create `Rng.java` in `dev.jeddy.math`:

```java
public final class Rng {
    private long s0;
    private long s1;
    private long s2;
    private long s3;

    /** Creates a generator from a seed. */
    public Rng(long seed) {
        setSeed(seed);
    }
```

The whole state is four `long`s: 256 bits. That is the entire save-game footprint of a generator.

### 12.1 Why not `s0 = seed`?

The tempting version — put the seed in `s0` and zeros elsewhere — fails twice:

- **Seed 0 gives an all-zero state**, and (as §13 shows) the step function maps all zeros to all
  zeros: the generator would output 0 forever.
- **Nearby seeds give nearby states.** Seeds 1 and 2 would differ in two bits out of 256, and the
  first outputs of the two generators would be strongly related. Players and tests use seeds 1, 2,
  3; they must give unrelated games.

So the seed is first spread over all 256 bits by a **mixing function**.

### 12.2 SplitMix64

```java
public void setSeed(long seed) {
    long x = seed;
    x += 0x9E3779B97F4A7C15L;
    s0 = mix(x);
    x += 0x9E3779B97F4A7C15L;
    s1 = mix(x);
    x += 0x9E3779B97F4A7C15L;
    s2 = mix(x);
    x += 0x9E3779B97F4A7C15L;
    s3 = mix(x);
}

private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
}
```

This is **SplitMix64**, itself a small generator, used here only to produce four well-mixed numbers.

- **The increment `0x9E3779B97F4A7C15`** is $2^{64}$ divided by the golden ratio ($\varphi \approx
  1.618$), rounded to an odd number. Being odd guarantees that adding it over and over visits all
  $2^{64}$ values of a `long` before repeating (an odd step and the power of two $2^{64}$ share no
  factor). The golden ratio spreads consecutive values as evenly as possible.
- **`mix`** alternates two operations. `z ^ (z >>> 30)` folds the high bits into the low bits (L00
  §6.1, §6.3). Multiplying by a large odd constant does the opposite: every bit of the product
  depends on all the bits *below* it in `z`, so low bits spread upward. Two rounds of each make
  every output bit depend on every input bit. The multiplications overflow, on purpose: Java's
  `long` arithmetic wraps (L00 §4.3), which is arithmetic modulo $2^{64}$, exactly what the algorithm
  specifies.

The result for seeds 1 and 2 (computed by running the code):

```
seed 1: s0 = 0x910a2dec89025cc1  s1 = 0xbeeb8da1658eec67  s2 = 0xf893a2eefb32555e  s3 = 0x71c18690ee42c90b
seed 2: s0 = 0x975835de1c9756ce  s1 = 0xbfc846100bfc1e42  s2 = 0x987bbcbfdd7e532f  s3 = 0xc3f2827affe7f664
```

Seeds that differ by 1 give states with no visible relation (the two `s0`s differ in 24 of their 64
bits; a perfect mixer differs in 32 on average). This property — a small change in the input flips
about half the output bits — is called **avalanche**.

### 12.3 Saving and restoring the state

```java
/** Copies the state into {@code out[0..3]}. */
public void saveState(long[] out) {
    out[0] = s0;
    out[1] = s1;
    out[2] = s2;
    out[3] = s3;
}

/** Restores a state written by {@link #saveState(long[])}. */
public void restoreState(long[] in) {
    if ((in[0] | in[1] | in[2] | in[3]) == 0) {
        throw new IllegalArgumentException("all-zero state");
    }
    s0 = in[0];
    s1 = in[1];
    s2 = in[2];
    s3 = in[3];
}
```

`saveState` writes into an array the caller owns, so saving allocates nothing. `restoreState`
refuses the all-zero state — OR-ing the four values is zero only if every bit of all four is zero —
because it is the one state the generator can never leave.

## 13. Step 2: 64 random bits — xoshiro256**

```java
/** Returns 64 random bits. */
public long nextLong() {
    long result = Long.rotateLeft(s1 * 5, 7) * 9;
    long t = s1 << 17;
    s2 ^= s0;
    s3 ^= s1;
    s1 ^= s2;
    s0 ^= s3;
    s2 ^= t;
    s3 = Long.rotateLeft(s3, 45);
    return result;
}
```

Two separate things happen here.

**The output (the "scrambler").** `result` is computed from `s1` alone, *before* the state changes:
multiply by 5, rotate left by 7 (L00 §6.3), multiply by 9. Hence the name: xoshiro256 followed by
two **star**s, the two multiplications. Its job is to hide the regularities of the state update
below from anyone looking at the outputs.

**The state update (the "linear engine").** The next seven lines only use XOR, shifts and rotations.
Each of those operations, applied to bits, is **linear** in the sense that every new bit is the XOR
of some old bits. The specific shifts and rotations (17 and 45) were chosen by the authors, by
computer search and proof, so that the 256-bit state goes through **every one of the $2^{256} - 1$
non-zero states** before repeating. That number is so large that a computer producing a billion
numbers a second since the Big Bang would not have made a dent in it.

And the one state it never reaches is the one it never leaves: if all four are zero, every XOR,
shift and rotation of zeros is zero. That is why seeding (§12.1) and restoring (§12.3) take care to
avoid it.

Why it is good enough: xoshiro256\*\* passes the standard statistical test batteries (BigCrush,
PractRand) and is among the fastest generators known: a handful of one-cycle instructions and two
multiplications. It is not **cryptographically secure** — someone who sees enough outputs can
compute the state and predict the rest — which matters for passwords and keys, and not for dice.

The first two outputs for seed 1 are −5,480,124,913,605,472,059 and −8,846,382,939,111,011,094 (as
signed `long`s). Negative numbers are fine: they are just 64 bits whose top bit happens to be 1.

**32 random bits**, when that is all you need, are the top half:

```java
public int nextInt() {
    return (int) (nextLong() >>> 32);
}
```

`>>> 32` moves the high 32 bits down (filling with zeros), and the cast keeps the low 32, which are
now those. The high half is used by habit and by caution: for some older generators the low bits
are the weak ones. For xoshiro256\*\* every bit is good.

## 14. Step 3: a fair number below a bound — Lemire's method

A die needs a number from a small range: 0 to 19 for a d20 (then add 1). We have 32 or 64 uniformly
random bits. How do we turn them into one of 20 values with *exactly* equal chances?

### 14.1 The obvious way is slightly unfair

`(nextLong() >>> 32) % 20`: 32 random bits as a non-negative number $x$, then $x \bmod 20$. (Not
`nextInt() % 20`: a negative `int` gives a negative remainder in Java, L00 §2.4.) The trouble: $x$ takes $2^{32} =
4{,}294{,}967{,}296$ values, which is not a multiple of 20. $2^{32} = 214{,}748{,}364 \times 20 +
16$, so the values 0 to 15 each come from 214,748,365 different $x$, and 16 to 19 from one fewer.
Those faces are *very slightly* less likely.

See it at a size you can count: a 3-bit generator (8 values, 0–7) and a bound of 3.

```
x:       0  1  2  3  4  5  6  7
x mod 3: 0  1  2  0  1  2  0  1      → 0 three times, 1 three times, 2 twice
```

Probabilities 3/8, 3/8, 2/8 instead of 1/3 each. For a d20 from 32 bits the bias is one part in
about 200 million: no player could ever notice. But a generator that is *exactly* fair costs no
more, so the engine uses one. (The `%` is also a division, tens of cycles; Lemire's method needs only
a multiplication.)

### 14.2 The multiply-and-shift idea

Instead of the remainder, use **multiplication**. Take $x$ in $[0, 2^{32})$ and compute $m = x \cdot
\text{bound}$, which lies in $[0, 2^{32} \cdot \text{bound})$. Split $m$ into its high 32 bits and its
low 32 bits:

- the **high part**, $\lfloor m / 2^{32} \rfloor$, is in $[0, \text{bound})$: it says which of
  `bound` equal slices of width $2^{32}$ the product fell into. That is the result.
- the **low part**, $m \bmod 2^{32}$, says where inside its slice.

As $x$ goes up by 1, $m$ goes up by `bound`, so the products step evenly through the slices. Each
slice receives either $\lfloor 2^{32}/\text{bound} \rfloor$ or one more of them — the same unevenness
as the remainder, just in different places. Here is the 3-bit version, with "high" meaning $m \gg
3$ and "low" $m \,\&\, 7$:

```
x:     0   1   2   3   4   5   6   7
m=3x:  0   3   6   9  12  15  18  21
high:  0   0   0   1   1   1   2   2      → 0 three times, 1 three times, 2 twice (still biased)
low:   0   3   6   1   4   7   2   5
```

### 14.3 Rejecting exactly the extra ones

The fix is to **reject** — throw away and draw again — exactly enough values to make every slice
equal. The number of extra values is $2^{32} \bmod \text{bound}$ (here $8 \bmod 3 = 2$), and Lemire
proved that rejecting the products whose **low part is below that number** removes exactly one extra
value from each over-full slice. In the table, the threshold is 2, and the values with low < 2 are
$x = 0$ (low 0) and $x = 3$ (low 1): one from slice 0 and one from slice 1. What remains:

```
x kept:  1   2   4   5   6   7
high:    0   0   1   1   2   2      → each result exactly twice: fair
```

Six values kept out of eight, each result with probability exactly 1/3 per kept draw. For a d20
from 32 bits the threshold is $2^{32} \bmod 20 = 16$, so a draw is rejected with probability $16 /
2^{32} \approx 3.7 \times 10^{-9}$: once in about 270 million rolls.

### 14.4 The code

```java
/** Returns a uniform int in {@code [0, bound)} without modulo bias (Lemire's method). */
public int nextInt(int bound) {
    if (bound <= 0) {
        throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    long m = (nextLong() >>> 32) * bound;
    long low = m & 0xFFFFFFFFL;
    if (low < bound) {
        long threshold = (0x1_0000_0000L - bound) % bound;
        while (low < threshold) {
            m = (nextLong() >>> 32) * bound;
            low = m & 0xFFFFFFFFL;
        }
    }
    return (int) (m >>> 32);
}
```

Line by line:

1. **`(nextLong() >>> 32) * bound`**: 32 random bits (as a non-negative `long`) times the bound. Both
   are below $2^{32}$ and $2^{31}$, so the product fits in a `long` without overflow.
2. **`m & 0xFFFFFFFFL`**: the low 32 bits. The `L` matters: `0xFFFFFFFF` without it is the `int`
   −1, which widened to `long` is all 64 bits set (L00 §5.4).
3. **`if (low < bound)`**: a shortcut. The threshold is always *less than* `bound` (it is a
   remainder after dividing by `bound`), so a low part of at least `bound` can never be rejected,
   and the code skips computing the threshold — which needs a real division — in all but a
   `bound`-in-$2^{32}$ fraction of calls.
4. **`(0x1_0000_0000L - bound) % bound`** is $2^{32} \bmod \text{bound}$: subtracting `bound`
   from a number does not change its remainder modulo `bound`. For 20: $(4{,}294{,}967{,}296 - 20)
   \bmod 20 = 16$. The subtraction comes from Lemire's original C code, which works in 32-bit
   arithmetic where $2^{32}$ itself does not fit; in Java, with a `long`, `0x1_0000_0000L % bound`
   would give the same number.
5. **The loop** draws again while the low part is in the rejected zone.
6. **`(int) (m >>> 32)`**: the high part, the result.

A d20 is therefore one 64-bit multiply, one comparison and, almost never, a second draw.

## 15. Step 4: dice, floats and the rest of the API

With fair integers, dice are one-liners:

```java
/** Returns a uniform int in {@code [min, max]} (inclusive). */
public int nextInt(int min, int max) {
    return min + nextInt(max - min + 1);
}

/** Rolls one die with {@code sides} faces: 1..sides. */
public int d(int sides) {
    return 1 + nextInt(sides);
}

/** Rolls {@code count} dice with {@code sides} faces and adds {@code modifier} (e.g. 2d6+3). */
public int roll(int count, int sides, int modifier) {
    int sum = modifier;
    for (int i = 0; i < count; i++) {
        sum += d(sides);
    }
    return sum;
}

/** Rolls a d20. */
public int d20() {
    return d(20);
}
```

`roll` really rolls each die and adds, which is what gives sums their bell (§5); it never computes
"one number in [count, count × sides]", which would be flat.

Fractions:

```java
/** Returns a uniform float in {@code [0, 1)}. */
public float nextFloat() {
    return (nextLong() >>> 40) * 0x1.0p-24f;
}

/** Returns a uniform double in {@code [0, 1)}. */
public double nextDouble() {
    return (nextLong() >>> 11) * 0x1.0p-53;
}
```

`>>> 40` keeps the top 24 bits: an integer from 0 to $2^{24} - 1$. Multiplying by $2^{-24}$ (written
as the hexadecimal float literal `0x1.0p-24f`, "1.0 times 2 to the −24") scales it into $[0, 1)$.
Why 24 bits: a `float` has exactly 24 significant bits (L00 §7.3), so every one of these $2^{24}$
values is represented exactly and they are evenly spaced. More bits would round, and rounding would
make some values more likely than others. A `double` has 53 significant bits, hence `>>> 11` and
$2^{-53}$.

```java
/** Returns {@code true} with probability 0.5. */
public boolean nextBoolean() {
    return nextLong() < 0;
}
```

A `long` is negative exactly when its top bit is set (L00 §5.2): one random bit.

The rest of the class, briefly:

- **`range(min, max)`**: `min + (max - min) * nextFloat()`, a float in $[\min, \max)$.
- **`nextGaussian()`**: a sample from the normal distribution of §5.3 (mean 0, standard deviation
  1), by Marsaglia's polar method: pick a random point in the square $[-1, 1)^2$, reject it if it is
  outside the unit circle, and transform the coordinates. Deterministic, like everything else here.
- **`pickWeighted(weights, n)`**: picks index $i$ with probability proportional to `weights[i]`
  (loot tables): draw $r$ in $[0, \text{total})$ and walk the weights subtracting until $r$ goes
  negative.
- **`jump()` and `split()`**: `jump` advances the state by $2^{128}$ steps at once (a precomputed
  polynomial, published with the algorithm), and `split` returns a copy at the old position and
  jumps itself, so the two can never produce overlapping sequences. This is requirement 4: combat,
  loot and AI each get a stream from one seed.

---

# Part C — Dice in an `int` and the rules that roll them

## 16. Step 5: `Dice` — NdS+B in 32 bits

### 16.1 Why pack dice into an `int`

Content describes damage as text: a longsword does `1d8`, a skeleton's blade `1d6+2`, a fireball
`5d6`. The cooker turns the rules' JSON into a binary `.jrules` file whose tables are columns of
plain numbers (chapter 15, D96, D97). A damage expression has three small parts — how many dice,
how many sides, a bonus — and if those fit in one `int`:

- a weapon table's damage column is an `int[]`: no objects, nothing for the garbage collector;
- rolling it allocates nothing;
- copying, comparing and saving it is copying an `int`.

Ranges that are plenty for any game: up to 255 dice of up to 255 sides, and a bonus from −32,768 to
32,767.

### 16.2 The layout

```
bit:  31 ........ 24 | 23 ........ 16 | 15 ............................ 0
      count (8 bits) | sides (8 bits) | bonus (16 bits, two's complement)
```

`0` means "no dice" (rolls 0); a flat number like 5 is `0d0+5`. Start `Dice.java` in
`dev.jeddy.math`: a class with only static methods and a private constructor, because an `int` is
the object.

```java
public final class Dice {
    private Dice() {}

    public static int pack(int count, int sides, int bonus) {
        if (count < 0 || count > 255 || sides < 0 || sides > 255) {
            throw new IllegalArgumentException("dice " + count + "d" + sides);
        }
        if (bonus < Short.MIN_VALUE || bonus > Short.MAX_VALUE) {
            throw new IllegalArgumentException("dice bonus " + bonus);
        }
        return count << 24 | sides << 16 | (bonus & 0xFFFF);
    }
```

This is L00 §6.4 exactly: validate each field against its width, shift it into place, OR them
together, and **mask the bonus to 16 bits** so a negative bonus's 16 extra one-bits do not spill
into the sides and count.

Worked examples (checked in `jshell`):

| Expression | count | sides | bonus | Packed |
|---|---|---|---|---|
| `1d8+4` | `0x01` | `0x08` | `0x0004` | `0x01080004` |
| `1d4-1` | `0x01` | `0x04` | `0xFFFF` (−1) | `0x0104FFFF` |
| `2d8+8` | `0x02` | `0x08` | `0x0008` | `0x02080008` |
| `5` | `0x00` | `0x00` | `0x0005` | `0x00000005` |

### 16.3 Reading the fields back

```java
public static int count(int dice) {
    return dice >>> 24;
}

public static int sides(int dice) {
    return dice >>> 16 & 0xFF;
}

public static int bonus(int dice) {
    return (short) (dice & 0xFFFF);
}
```

- **`count`**: shift the top byte down. `>>>` fills with zeros, so no mask is needed (with `>>`, a
  count of 128 or more would come back negative).
- **`sides`**: shift the second byte down, then mask off the count that came with it. Java's `>>>`
  binds tighter than `&`, so no parentheses are needed.
- **`bonus`**: keep the low 16 bits, then cast to `short`, which reinterprets them as a signed
  16-bit number: `0xFFFF` becomes −1, `0x0004` stays 4. Casting back to `int` (implicitly, on
  return) sign-extends it (L00 §5.4).

### 16.4 Changing an expression

```java
/** Returns {@code dice} with {@code delta} added to its bonus. */
public static int plus(int dice, int delta) {
    return pack(count(dice), sides(dice), bonus(dice) + delta);
}

/** Returns {@code dice} with its count multiplied (a critical hit multiplies the dice). */
public static int times(int dice, int factor) {
    return pack(Math.min(255, count(dice) * factor), sides(dice), bonus(dice) * factor);
}
```

Unpack, change, repack: `pack` re-validates, so an out-of-range bonus throws instead of corrupting
neighbouring bits. `times` multiplies the **count and the bonus**, giving the distribution of "roll
it `factor` times and add" (§5.4): `times(1d8+4, 2)` is `2d8+8`. The count is capped at 255, the
field's maximum.

### 16.5 Rolling and describing

```java
/** Rolls the dice: the sum of {@code count} rolls of {@code sides} plus the bonus. */
public static int roll(int dice, Rng rng) {
    int n = count(dice);
    int s = sides(dice);
    int total = bonus(dice);
    if (s > 0) {
        for (int i = 0; i < n; i++) {
            total += rng.nextInt(s) + 1;
        }
    }
    return total;
}

/** Returns the expected value, {@code count * (sides + 1) / 2 + bonus}. */
public static float average(int dice) {
    return count(dice) * (sides(dice) + 1) * 0.5f + bonus(dice);
}

public static int min(int dice) {
    return (sides(dice) > 0 ? count(dice) : 0) + bonus(dice);
}

public static int max(int dice) {
    return count(dice) * sides(dice) + bonus(dice);
}
```

- **`roll`** takes the generator as a parameter: `Dice` has no state, and whoever owns the `Rng`
  decides which stream the roll comes from (§15). The `s > 0` guard is for an expression like
  `3d0` (dice with zero sides), which would otherwise call `nextInt(0)` and throw; a flat number
  (`0d0+5`) has a count of 0, so its loop never runs anyway.
- **`average`** is §3.3's formula. `0.5f` makes the arithmetic `float`, so `(sides + 1) / 2` does
  not round down in integer division (1d8 averages 4.5, not 4).
- **`min`** is "every die shows 1": `count` (unless there are no sides, then 0) plus the bonus.
  **`max`** is "every die shows `sides`".

### 16.6 Parsing text

The cooker reads `"2d6+3"` from JSON with `Dice.parse(CharSequence)`. It accepts `"2d6+3"`, `"d8"`
(a missing count means 1), `"1d4-1"`, `"5"` (a flat number) and `""` (no dice), with spaces around.
It is a small hand-written scanner — the same technique, on a bigger scale, as M17's dialogue
compiler. The heart of it:

```java
int count = 0;
int sides = 0;
int bonus = 0;
int digits = 0;
int value = 0;
while (i < n && Character.isDigit(text.charAt(i))) {     // an optional number
    value = value * 10 + (text.charAt(i) - '0');
    i++;
    digits++;
}
if (i < n && (text.charAt(i) == 'd' || text.charAt(i) == 'D')) {
    count = digits == 0 ? 1 : value;                     // "d8" means 1d8
    ...                                                  // read the sides' digits (required)
    ...                                                  // then an optional '+' or '-' and digits
} else {
    if (digits == 0) {
        throw new IllegalArgumentException("not a dice expression: " + text);
    }
    bonus = value;                                       // "5" is 0d0+5
}
```

`value * 10 + (c - '0')` builds a number digit by digit: `'7' - '0'` is 7, because digit characters
are consecutive codes. `digits` counts how many were read, to tell "no number" from "the number 0".
Each part that must exist (the sides after `d`, digits after `+`) throws a message naming the
problem. And since `parse` returns `pack(count, sides, bonus)`, the range checks of §16.2 apply to
text too. Exercise 7 asks you to write the full method from this outline.

## 17. Step 6: `Rules.check`

The check of §6, in the rules module:

```java
public boolean check(int modifier, int dc) {
    lastNatural = rng.d20();
    lastTotal = lastNatural + modifier;
    return lastNatural == 20 || (lastNatural != 1 && lastTotal >= dc);
}
```

- **The return line is §6.2's rule**, read left to right: a natural 20 succeeds; otherwise a natural
  1 fails; otherwise compare the total with the DC.
- **`lastNatural` and `lastTotal`** are fields, set on every call. The caller needs more than
  true/false: the attack needs the natural roll to test the threat range, and the event log records
  both numbers ("rolled 14 + 10 = 24 against AC 17"). Returning an object with three fields would
  allocate on every roll; returning them packed would make every caller unpack. A field the caller
  reads right after the call costs nothing. (This is safe because a `Rules` belongs to one thread,
  the game thread.)
- **A saving throw is this same method**, with the save bonus as `modifier` and the spell's DC.

## 18. Step 7: `Rules.attack`

`attack(attacker, target, index, bonus, flags)` resolves one attack roll, the `index`-th of a full
attack, with an extra `bonus` the game decides (flanking, a charge). It returns `MISS`, `HIT` or
`CRIT`. The full method is about 70 lines. It begins with `if (!canAct(attacker) || isDead(target))
return MISS;` (a paralysed attacker or a dead target), and the rest is in four stages.

### 18.1 Where the numbers come from

```java
int weapon = creatures.equipment(attacker, Creatures.SLOT_WEAPON);
int monsterAttack = creatures.monsterAttack(attacker);
int dice; int threat; int multiplier; int type; int attackBonus;
int damageBonus = creatures.stat(attacker, Stat.DAMAGE);
if (monsterAttack >= 0 && creatures.monsterAttackCount(attacker) > 0) {
    int row = monsterAttack + index % creatures.monsterAttackCount(attacker);
    dice = content.attackDamage(row);
    threat = content.attackThreat(row);
    multiplier = content.attackCritical(row);
    type = content.attackDamageType(row);
    // A stat block's attack bonus already includes Strength and size: use it plus modifiers.
    attackBonus = content.attackBonus(row) + creatures.bonus(attacker, Stat.ATTACK)
            - (index > 0 ? 5 * index : 0);
    damageBonus = creatures.bonus(attacker, Stat.DAMAGE); // the dice carry Strength
} else if (weapon >= 0) {
    dice = content.itemDamage(weapon);
    threat = content.itemThreat(weapon);
    multiplier = content.itemCritical(weapon);
    type = content.itemDamageType(weapon);
    attackBonus = attackBonus(attacker, index);
} else {
    dice = Dice.pack(1, 3, 0); // an unarmed strike
    threat = 20;
    multiplier = 2;
    type = 0;
    attackBonus = attackBonus(attacker, index);
}
attackBonus += bonus;
int ac = armorClass(target, flags);
```

(The declarations are joined on one line here; the file has one per line.) Three sources:

- **A monster's stat block** lists its attacks with a bonus and damage already including Strength
  and size, as printed monster entries do (`"bonus": 4, "damage": "1d6+2"` for the skeletal
  warrior). So only *modifiers* from effects are added (a curse, a blessing), and the damage bonus
  is only modifiers too — the `+2` is inside the dice.
- **A character's weapon** gives the dice, threat and multiplier; the attack bonus comes from
  `attackBonus(attacker, index)`: base attack bonus + Strength (or Dexterity for a finesse weapon)
  + size + modifiers, minus 5 per iterative attack.
- **No weapon**: an unarmed strike, `1d3`, threat 20, ×2.

Then `armorClass` computes the target's AC for this kind of attack (a touch attack ignores armor).

### 18.2 The roll and the threat

```java
boolean hit = check(attackBonus, ac);
int natural = lastNatural;
int total = lastTotal;
if (!hit) {
    events.add(Events.ATTACK_MISS, attacker, target, natural, total, ac, index, weapon);
    return MISS;
}
int result = HIT;
if (natural >= threat && natural != 1) {
    // Confirm: a second roll against the same AC.
    if (check(attackBonus, ac)) {
        result = CRIT;
    }
}
```

- `natural` and `total` are copied out of the fields at once, because the confirmation roll will
  overwrite them.
- The threat is tested **only after a hit** (§8.3): a natural 19 that misses does not threaten.
- `natural != 1` can never matter here: a natural 1 always misses, so `attack` has already
  returned `MISS` above. It restates the rule for the reader.
- The confirmation is a second, independent `check` with the same numbers.

### 18.3 Events

```java
events.add(
        result == CRIT ? Events.CRITICAL : Events.ATTACK_HIT,
        attacker, target, natural, total, ac, index, weapon);
```

The rules never format a string. They append numbers to an **event ring** (chapter 15): the kind,
who, whom, the natural roll, the total, the AC. The demo's combat log, the floating damage numbers
and the tests read the events; this is how the tests in Part D can check every roll.

### 18.4 Damage

```java
int rolled = Dice.roll(dice, rng) + damageBonus;
if (result == CRIT) {
    for (int k = 1; k < multiplier; k++) {
        rolled += Dice.roll(dice, rng) + damageBonus;
    }
}
damage(target, Math.max(1, rolled), type, attacker, weapon);
return result;
```

- A critical **rolls `multiplier` times**, the bonus included each time (§5.4: the same mean as
  multiplying, less spread).
- **`Math.max(1, rolled)`**: a hit always does at least 1, even `1d4-1` rolling a 1 (which would be
  0). This changes the mean: `1d4-1` averages 1.5 as dice, but $(1 + 1 + 2 + 3)/4 = 1.75$ as damage.
- `damage` takes temporary hit points first, lowers the hit points, records a `DAMAGE` event and
  checks for dying and death (which record their own events).

### 18.5 A real inconsistency, found while writing this lesson

Look at the two ways the attack bonus is computed for the `index`-th attack. For a character,
`attackBonus(attacker, index)` subtracts 5 only for *iterative* attacks (those from a high base
attack bonus) and gives extra attacks from effects — like the two-weapon fighting feat — the full
bonus. For a monster, the stat-block branch subtracts `5 * index` for **every** attack after the
first. Running the demo's numbers (a scratch test printing them):

- Dara (two-weapon fighting): attacks at **+8 / +8**.
- The skeletal stalker (also two-weapon fighting, stat-block bonus +4, −2 from the feat): attacks at
  **+2 / −3**.

Whether a monster's extra attacks should come at full bonus is a rules decision for the game's
owner; the lesson records the behaviour as it is, and exercise 3 computes what it costs the
stalker.

---

# Part D — Testing randomness

## 19. Fix the seed, test the logic

How do you test "a natural 1 always misses"? Rolling until a 1 comes up makes a test that sometimes
takes one roll and sometimes fifty. Asserting over a thousand rolls is slow and can still, very
rarely, be unlucky. The engine's tests take a third way: **choose the seed**.

```java
/** The first seed whose first d20 is {@code wanted}. */
private static long seedWhereFirstD20Is(int wanted) {
    for (long s = 1; ; s++) {
        if (new Rng(s).d20() == wanted) {
            return s;
        }
    }
}

/** The first seed whose first two d20s are {@code a} then {@code b}. */
private static long seedWhereD20sAre(int a, int b) {
    for (long s = 1; ; s++) {
        Rng x = new Rng(s);
        if (x.d20() == a && x.d20() == b) {
            return s;
        }
    }
}
```

Each seed's first roll is uniform, so a search for one face takes about 20 seeds and for two faces
about 400. With the real generator the first seed whose first d20 is 1 is **9**, whose first d20 is
20 is **10**, whose first two are 20 then 1 is **73**, and 20 then 20 is **765**. The test then knows
exactly what will be rolled:

```java
@Test
void naturalOneMissesAndNaturalTwentyHits() {
    Rules r = rules(seedWhereFirstD20Is(1));
    int dara = character(r, "dara");
    int skeleton = monster(r, "skeletalWarrior");
    assertEquals(Rules.MISS, r.attack(dara, skeleton, 0, 100, 0)); // +100 does not help a 1
    assertEquals(1, r.lastNatural());
    r.rng().setSeed(seedWhereD20sAre(20, 1));
    // A 20 hits whatever the penalty; the 1 that follows fails the confirmation.
    long first = r.events().next();
    assertEquals(Rules.HIT, r.attack(dara, skeleton, 0, -100, 0));
    assertEquals(20, r.events().natural(first));
    assertEquals(1, r.lastNatural()); // the failed confirmation was the last roll
}
```

**The oracle.** `anAttackRollIsD20PlusBonusAgainstArmorClass` goes further: for 40 seeds, it builds a
second `Rng` with the same seed — an **oracle** — and replays by hand what `attack` must do: roll a
d20, add 10, compare with 17, roll the confirmation if the natural is 19 or more, roll the damage
dice. Then it checks that the events contain exactly those numbers. Because the oracle and the rules
draw from identical sequences in the same order, the test checks not only the arithmetic but also
**how many times** and **in which order** the rules roll, which is what determinism depends on.

**Replays.** `aSeedReplaysTheSameFight` runs a fight of up to 20 rounds (it stops early when both
skeletons are dead) twice with seed 42, hashes the kind and amount of every event, and asserts the
hashes and the final hit points are equal — and that seed 43 changes the hash or the hit points. If any code ever rolled from a
different generator, or rolled a different number of times depending on something outside the seed
(the time, a hash order), this test would catch it.

The general technique: **fix the randomness, test the logic.**

## 20. When the question is statistical

Sometimes the question *is* about randomness: is the die fair? Then the test must accept some
tolerance, and the tolerance should come from the math, not from a guess.

### 20.1 How much a count wanders

Roll a die $N$ times and count how often face 1 comes up. Each roll is a trial that "succeeds" with
probability $p$. The count is a sum of $N$ independent 0-or-1 variables, each with mean $p$ and
variance $p(1 - p)$ (use §4.2: $E[X^2] = p$ because $0^2 = 0$ and $1^2 = 1$, so the variance is $p -
p^2$). Means and variances add (§5.2), so the count has

$$\text{mean } Np, \qquad \sigma = \sqrt{N p (1 - p)}.$$

This is the **binomial distribution**. For large $N$ it is close to normal (§5.3), and for a normal
variable, landing more than $z$ standard deviations from the mean has a known, rapidly shrinking
probability: about 32 % beyond 1σ, 5 % beyond 2σ, 0.3 % beyond 3σ, and about $4 \times 10^{-8}$
beyond 5.5σ.

### 20.2 The engine's test

`GeometryAndUtilTest.rngIsDeterministicAndUniform` rolls 60,000 d6 with seed 7 and asserts each face
count is within 500 of 10,000:

```java
int[] counts = new int[6];
Rng r = new Rng(7);
for (int i = 0; i < 60_000; i++) {
    int v = r.d(6);
    assertTrue(v >= 1 && v <= 6);
    counts[v - 1]++;
}
for (int c : counts) {
    assertTrue(Math.abs(c - 10_000) < 500, "die face count " + c);
}
```

Put §20.1 on it: $\sigma = \sqrt{60{,}000 \cdot \frac16 \cdot \frac56} \approx 91.3$, so the tolerance
of 500 is $500 / 91.3 \approx 5.5\sigma$.

- **Would a fair die fail it?** A face outside 5.5σ has probability about $4.3 \times 10^{-8}$; with
  six faces, about one run in 3.9 million. (With a fixed seed the test is deterministic anyway; the
  number says the tolerance would be safe for *any* seed.)
- **Would a biased die pass it?** Suppose a bug made one face come up 1 percentage point too often,
  17.67 % instead of 16.67 %: its expected count is 10,600, above the bound of 10,500, and its own
  σ is about 93, so it crosses the bound in about 86 % of runs.

That is what "a tolerance from the variance" means: wide enough never to fail by chance, narrow
enough to catch the bug you are guarding against.

### 20.3 How many rolls you need

Turn it around: to detect a bias of $\varepsilon$ in a probability $p$ at $z$ standard deviations,
you need the bias in the count ($N\varepsilon$) to be at least $z\sigma = z\sqrt{Np(1-p)}$. Square both
sides and solve for $N$:

$$N \ge \frac{z^2 \, p(1 - p)}{\varepsilon^2}.$$

For a d20 face ($p = 0.05$), a one-point bias ($\varepsilon = 0.01$), at $z = 3$: $N \ge 9 \times
0.0475 / 0.0001 = 4{,}275$ rolls. For the modulo bias of §14.1 (each of the four short faces is off
by $\varepsilon \approx 1.9 \times 10^{-10}$) the same formula asks for about $10^{19}$ rolls — which is why nobody could ever
notice it, and why exercise 5 needs a cleverer bound to see it.

## 21. Things to run

- `./gradlew :rules:test --tests 'dev.jeddy.rules.RulesTest'`: §17–19 as code.
- `./gradlew :math:test --tests 'dev.jeddy.math.GeometryAndUtilTest'`: the uniformity and
  determinism test of §20.
- `./gradlew :iso-demo:run --args="--turn-based --echo-combat"` prints every attack as
  "Aldric hits Skeletal warrior (21 vs AC 17)": the total against the AC. Subtract the attacker's
  bonus (§8 and §18.5 list the demo's) to get the natural roll, count hits over a few rounds, and
  compare with §6's table.
- In `jshell`, with the math module's classes on the class path
  (`jshell --class-path modules/math/build/classes/java/main` after a build):
  `var r = new dev.jeddy.math.Rng(1); r.d20()` gives 15, then 11, 12, 8, 14.
- A Monte Carlo of §10, as a scratch test in `modules/rules/src/test` (criticals left out, to match
  the 3.33 of the recursion):

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

## 22. Exercises

**1. Squares and variance.** Check $\sum_{k=1}^{S} k^2 = S(S+1)(2S+1)/6$ for $S = 1, 2, 4$, then
compute the variance of a d4 both from a table of squared distances (§4.1) and from $(S^2 - 1)/12$.

<details><summary>Solution</summary>

$S = 1$: 1 and $1 \cdot 2 \cdot 3/6 = 1$. $S = 2$: 5 and $2 \cdot 3 \cdot 5/6 = 5$. $S = 4$: 30 and
$4 \cdot 5 \cdot 9/6 = 30$. d4: mean 2.5; distances −1.5, −0.5, 0.5, 1.5; squares 2.25, 0.25, 0.25,
2.25; average $5/4 = 1.25$; formula $(16 - 1)/12 = 1.25$. ✓
</details>

**2. The 3d6 tail.** Compute $P(3d6 \ge 16)$ by convolving the 2d6 triangle with one more d6.

<details><summary>Hint</summary>$P(T_3 = t) = \sum_f P(T_2 = t - f)/6$; you only need $t = 16, 17, 18$.</details>
<details><summary>Solution</summary>

In counts out of 216 (2d6 counts out of 36, times 1 for each face of the third die): $t = 18$ needs
$T_2 = 12$ (face 6): 1. $t = 17$: $T_2 = 12$ (face 5) or 11 (face 6): $1 + 2 = 3$. $t = 16$: $T_2 =
12, 11, 10$ (faces 4, 5, 6): $1 + 2 + 3 = 6$. Total $10/216 \approx 0.046$.
</details>

**3. The stalker's two knives.** The skeletal stalker attacks Aldric (AC 20) twice per round with
`1d4+1` (threat 19–20, ×2), at +2 and −3 (§18.5). Compute its expected damage per round, and compare
with a skeletal warrior's single attack at +4 with `1d6+2` (19–20, ×2). Then recompute the stalker
as if both attacks were at +2.

<details><summary>Solution</summary>

First knife: $n = 18$, $p = 0.15$, $q_1 = 0.10$: $E = 0.15 \cdot 3.5 + 0.10 \cdot 0.15 \cdot 3.5 =
0.5775$. Second: $n = 23$, only a natural 20 hits, $p = 0.05$, and only the 20 threatens, $q_1 =
0.05$: $E = 0.175 + 0.05 \cdot 0.05 \cdot 3.5 \approx 0.184$. Round: 0.761. Warrior: $n = 16$, $p =
0.25$: $E = 0.25 \cdot 5.5 + 0.10 \cdot 0.25 \cdot 5.5 = 1.5125$. With both knives at +2 the stalker
would do $2 \times 0.5775 = 1.155$: the −5 on its second attack costs it about a third of its damage.
</details>

**4. Rolling against multiplying.** Change the critical in `Rules.attack` to roll once and multiply,
and find the test that fails. Then compute both variances for a ×3 greataxe doing `1d12`.

<details><summary>Solution</summary>

`aConfirmedThreatMultipliesTheDamage` fails: its oracle rolls the axe three times
(`expected += Dice.roll(axe, oracle) + 9` in a loop), so a single roll tripled gives a different
number (and consumes fewer random numbers, shifting everything after). Variances: rolled three
times, $3 \times 143/12 \approx 35.75$ ($\sigma \approx 5.98$); one roll tripled, $9 \times 143/12
\approx 107.25$ ($\sigma \approx 10.36$). Same mean (19.5) and same range (3 to 36), very different
spread.
</details>

**5. Seeing the modulo bias.** Replace `nextInt(bound)` with `(int) ((nextLong() >>> 32) % bound)` in
a copy of `Rng`. Use §20.3 to show that no test can see the bias for `bound = 20`. Then find a bound
where it is obvious, and write the test.

<details><summary>Hint</summary>The bias is large when $2^{32} \bmod \text{bound}$ is a large
fraction of `bound`. Try `bound = 1_500_000_000`.</details>
<details><summary>Solution</summary>

For 20, faces 0–15 are each too likely by $0.2 / 2^{32} \approx 4.7 \times 10^{-11}$ and faces
16–19 too unlikely by $0.8 / 2^{32} \approx 1.9 \times 10^{-10}$. Even for the larger bias, §20.3
asks for $9 \times 0.0475 / (1.9 \times 10^{-10})^2 \approx 1.2 \times 10^{19}$ rolls: centuries at
a billion rolls a second. For bound $b =
1.5 \times 10^9$: $2^{32} = 2b + 1{,}294{,}967{,}296$, so results below 1,294,967,296 have three
preimages and the rest two. With modulo, $P(\text{result} < 1{,}294{,}967{,}296) = 3 \times
1{,}294{,}967{,}296 / 2^{32} \approx 0.905$; fair, it is $1{,}294{,}967{,}296 / b \approx 0.863$. A
test that draws 10,000 values and asserts the fraction below that number is within 0.015 of 0.863
passes with Lemire's method (0.015 is about 4.4σ, since $\sigma = \sqrt{0.863 \cdot 0.137 / 10{,}000}
\approx 0.0034$) and fails with modulo (the difference, 0.042, is about 12σ).
</details>

**6. Dara's two daggers.** Dara attacks twice per round at +8 with `1d4-1` (19–20, ×2, at least 1
damage per hit) against a skeletal warrior (AC 17, 16 hit points). Write the Markov chain of §10 for
the number of *rounds*, and check it with a Monte Carlo.

<details><summary>Hint</summary>The state is the hit points left. Work out the damage distribution
of one attack (miss, normal hit, critical — remember `Math.max(1, …)`), combine two attacks into a
round, and note a round can do 0 damage, which makes the equation refer to $R(r)$ on both sides.</details>
<details><summary>Solution</summary>

$n = 9$, $p = 0.6$, $q_1 = 0.10$, so per attack: miss 0.4; normal hit $0.6 - 0.06 = 0.54$ with
damage `max(1, d4 - 1)` ∈ {1, 1, 2, 3}; critical 0.06 with damage `max(1, (d4 - 1) + (d4 - 1))`. One
attack averages 1.129. A round (two independent attacks) does 0 with probability $0.4^2 = 0.16$.
With $P_d$ the probability a round does $d$:

$$R(r) = 1 + P_0 R(r) + \sum_{d \ge 1} P_d R(r - d) \;\Rightarrow\; R(r) = \frac{1 + \sum_{d \ge 1} P_d R(r - d)}{1 - P_0}.$$

$R(16) \approx 7.65$ rounds. A Monte Carlo of 200,000 fights gives 7.65 as well. (Aldric needs about
3.1 swings for the same skeleton: daggers are for flanking, not for this.)
</details>

**7. Write `Dice.parse`.** From the outline in §16.6, write the whole method, including the trailing
spaces and the error messages, and a test that checks `"2d6+3"`, `"d8"`, `" 1d4-1 "`, `"5"`, `""` and
that `"2d"`, `"d"`, `"x"` and `"1d6+"` throw.

<details><summary>Solution</summary>

Compare with `Dice.parse` in the repository; a test in the style of the engine's:
`assertEquals(Dice.pack(2, 6, 3), Dice.parse("2d6+3"))`, `assertEquals(Dice.pack(1, 8, 0),
Dice.parse("d8"))`, `assertEquals(0, Dice.parse(""))`, and
`assertThrows(IllegalArgumentException.class, () -> Dice.parse("1d6+"))` for each bad input.
</details>

**8. Why SplitMix?** If `setSeed` set `s0 = seed` and the other three to 0, what would `new
Rng(0).nextLong()` return, and the call after it?

<details><summary>Solution</summary>

0 and 0, forever: the all-zero state maps to itself (§13), and the output of `s1 = 0` is
`rotateLeft(0, 7) * 9 = 0`.
</details>

## 23. Glossary

- **Avalanche:** a small change of input flips about half the output bits (§12.2).
- **Bernoulli trial:** an experiment with two outcomes (§7).
- **Binomial distribution:** the count of successes in $N$ independent trials (§20.1).
- **Bonus, DC, AC:** what a character adds to a d20; the number a check must reach; the DC of an
  attack (§6.1).
- **Central limit theorem:** sums of many independent variables are close to normal (§5.3).
- **Check:** d20 + bonus against a DC, natural 20 succeeds, natural 1 fails (§6).
- **Conditional probability $P(A \mid B)$:** the probability of $A$ among the outcomes where $B$
  happened (§8.2).
- **Confirmation, threat range, multiplier, critical:** the second roll, the faces that may
  confirm, how many times a critical rolls, the result (§8.1).
- **Convolution:** combining distributions to get the distribution of a sum (§5.3).
- **Distribution:** each value of a random variable with its probability (§3.1).
- **Event, outcome:** a set of outcomes; one possible result (§1).
- **Expected value (mean) $E[X]$:** the long-run average, $\sum x P(X = x)$ (§3.2).
- **Independent:** one result does not change the other's chances (§2.2).
- **Lemire's method:** an unbiased bounded integer by multiplication and rejection (§14).
- **Markov chain:** states with probabilities of moving between them; here, hit points left (§10).
- **Monte Carlo:** estimating by simulating many times (§10).
- **Normal distribution:** the bell curve (§5.3).
- **Oracle:** a second generator with the same seed that predicts what the code must roll (§19).
- **Period:** outputs before a generator repeats (§11).
- **PRNG, seed, state:** a deterministic generator of random-looking numbers; the number it starts
  from; the numbers it keeps (§11).
- **Rejection:** discarding a draw and drawing again to remove bias (§14.3).
- **SplitMix64, xoshiro256\*\*:** the seed mixer and the bit generator `Rng` uses (§12–13).
- **Variance, standard deviation σ:** the average squared distance from the mean; its square root
  (§4).

*Code this lesson builds or quotes: `math.Rng`, `math.Dice`, `rules.Rules.check`,
`rules.Rules.attack`, `RulesTest`, `GeometryAndUtilTest`. Decisions: D96 (the rules core), D99
(checks, attacks and saves; dice packed into an `int`) in [DESIGN §17](../../DESIGN.md) and §20; the
generator in DESIGN §8.3.*
