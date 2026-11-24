<?php
// fast-anything's PHP recorder, prepended with -d auto_prepend_file for one run.
final class FastAnythingRec
{
    public static bool $inside = false;
    private static array $kept = [];
    private static array $args = [];
    private static int $calls = 0;
    private static int $skipped = 0;
    private static int $nanos = 0;
    private static array $why = [];
    private static $fn = null;
    private static array $params = [];

    /** Missing optional arguments, filled in from the declaration like PHP does. */
    public static function full(string $name, array $args): array
    {
        $ps = self::$params[$name] ??= (str_contains($name, '::') ? new ReflectionMethod(...explode('::', $name, 2)) : new ReflectionFunction($name))->getParameters();
        for ($i = count($args); $i < count($ps); $i++) {
            if (!$ps[$i]->isDefaultValueAvailable()) break;
            $args[] = $ps[$i]->getDefaultValue();
        }
        return $args;
    }

    public static function enc($v): string
    {
        self::check($v);
        return json_encode(self::wire($v), JSON_PRESERVE_ZERO_FRACTION | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    }

    // NaN and infinity, which JSON has no numbers for, travel as {"$float": "NaN"} (or
    // "Infinity", "-Infinity"), as the port's C entry point reads and writes them
    private static function wire($v)
    {
        if (is_float($v) && !is_finite($v)) return ['$float' => is_nan($v) ? 'NaN' : ($v > 0 ? 'Infinity' : '-Infinity')];
        return is_array($v) ? array_map([self::class, 'wire'], $v) : $v;
    }

    private static function check($v): void
    {
        if (is_array($v)) { foreach ($v as $x) self::check($x); return; }
        if (is_string($v) && !preg_match('//u', $v)) throw new InvalidArgumentException('binary string');
        if (is_object($v)) throw new InvalidArgumentException(get_class($v) . ' object');
        if (is_resource($v)) throw new InvalidArgumentException('resource');
    }

    public static function record(string $name, array $args, callable $fn)
    {
        $args = self::full($name, $args);
        self::$fn = $fn;
        self::$inside = true;
        try {
            self::$calls++;
            $limit = (int) (getenv('FAST_ANYTHING_RECORD_LIMIT') ?: 500);
            $slot = count(self::$kept) < $limit ? count(self::$kept) : random_int(0, self::$calls - 1);
            $enc = null;
            if ($slot < $limit) {
                try { $enc = self::enc($args); } catch (Throwable $e) { self::$skipped++; self::$why[$e->getMessage()] = true; }
            }
            $t = hrtime(true);
            try {
                $r = $fn(...$args);
            } catch (Throwable $e) {
                self::$nanos += hrtime(true) - $t;
                if ($enc !== null) self::store($slot, '{"args":' . $enc . ',"raises":' . json_encode(get_class($e)) . '}', null);
                throw $e;
            }
            self::$nanos += hrtime(true) - $t;
            if ($enc !== null) {
                try { self::store($slot, '{"args":' . $enc . ',"result":' . self::enc($r) . '}', $args); }
                catch (Throwable $e) { self::$skipped++; self::$why[$e->getMessage()] = true; }
            }
            return $r;
        } finally {
            self::$inside = false;
        }
    }

    private static function store(int $slot, string $line, ?array $args): void
    {
        self::$kept[$slot] = $line;
        self::$args[$slot] = $args;
    }

    private static function variants($v): array
    {
        $out = [];
        if (is_string($v)) {
            $chars = preg_split('//u', $v, -1, PREG_SPLIT_NO_EMPTY);
            if ($chars === false) return [];
        } elseif (is_array($v) && array_is_list($v)) {
            $chars = $v;
        } else {
            return [];
        }
        $n = count($chars);
        if ($n < 2) return [];
        mt_srand(0xFA57);
        foreach ([0, 1, 2, 3, 5, 8, 13, 21, 34, 55] as $len) {
            if ($len >= $n) break;
            for ($rep = 0; $rep < ($len < 2 ? 1 : 3); $rep++) {
                $slice = array_slice($chars, mt_rand(0, $n - $len), $len);
                $out[] = is_string($v) ? implode('', $slice) : $slice;
            }
        }
        return $out;
    }

    // an accent, CJK, an emoji with a modifier, a combining mark
    private const NON_ASCII = ["\u{e9}", "\u{65e5}\u{672c}", "\u{1f44d}\u{1f3fd}", "e\u{301}"];

    /** Inputs where ports and originals tend to part ways, of the same type as the recorded value. */
    private static function edges($v): array
    {
        $out = [];
        if (is_int($v)) {
            foreach ([0, 1, -1] as $x) if ($x !== $v) $out[] = $x;
        } elseif (is_float($v)) {
            foreach ([0.0, -0.0, 1.0, -1.0, 1e300, 5e-324, NAN, INF, -INF] as $x) if ($x !== $v) $out[] = $x;
        } elseif (is_string($v)) {
            $chars = preg_split('//u', $v, -1, PREG_SPLIT_NO_EMPTY);
            if ($chars === false) return [];
            $mid = intdiv(count($chars), 2);
            $a = self::NON_ASCII;
            $head = implode('', array_slice($chars, 0, $mid));
            $tail = implode('', array_slice($chars, $mid));
            foreach ([$head . $a[0] . $tail, $v . $a[1], $a[2] . $v, $v . $a[3], ''] as $x) if ($x !== $v) $out[] = $x;
        } elseif (is_array($v) && $v && array_is_list($v)) {
            $out[] = [];
            foreach (array_slice(self::edges($v[0]), 0, 4) as $e) {
                $c = $v;
                $c[0] = $e;
                $out[] = $c;
            }
        }
        return $out;
    }

    /** A short version of a recorded value, so edge cases stay cheap when the real inputs are big. */
    private static function small($v)
    {
        if (is_string($v)) {
            $chars = preg_split('//u', $v, -1, PREG_SPLIT_NO_EMPTY);
            return $chars !== false && count($chars) > 64 ? implode('', array_slice($chars, 0, 64)) : $v;
        }
        return is_array($v) && array_is_list($v) && count($v) > 8 ? array_slice($v, 0, 8) : $v;
    }

    /** The value repeated $factor times (null past 4096 items or characters). */
    private static function bigger($v, int $factor)
    {
        if (is_string($v)) {
            $n = preg_match_all('/./su', $v);
            return $n && $n * $factor <= 4096 ? str_repeat($v, $factor) : null;
        }
        if (is_array($v) && $v && array_is_list($v) && count($v) * $factor <= 4096) {
            return array_merge(...array_fill(0, $factor, $v));
        }
        return null;
    }

    /** How long the original took on $args (ns), or null if it threw: outside its domain, not a fair test. */
    private static function attempt(array $args, array &$derived, array &$seen, string $tag): ?int
    {
        try {
            $enc = self::enc($args);
            if (isset($seen[$enc])) return null;
            $seen[$enc] = true;
            $t = hrtime(true);
            $r = (self::$fn)(...$args);
            $took = hrtime(true) - $t;
            $derived[] = '{"args":' . $enc . ',"result":' . self::enc($r) . ',"derived":' . $tag . '}';
            return $took;
        } catch (Throwable $e) {
            return null;
        }
    }

    public static function save(): void
    {
        self::$inside = true;
        $derived = [];
        $seen = [];
        // Edge cases first, from a sample of recorded calls: special numbers, non-ASCII text, empty and
        // much bigger inputs. The original answers them; inputs it throws on are skipped.
        $start = hrtime(true);
        $edgeLeft = fn() => 1.5e9 - (hrtime(true) - $start);
        $grown = [];
        $sampled = 0;
        if (self::$fn !== null) {
            foreach (self::$args as $args) {
                if ($args === null) continue;
                if (++$sampled > 20) break;
                $base = array_map(fn($a) => self::small($a), $args);
                foreach ($base as $i => $arg) {
                    foreach (self::edges($arg) as $e) {
                        if (count($derived) >= 100 || $edgeLeft() <= 0) break 3;
                        $next = $base;
                        $next[$i] = $e;
                        self::attempt($next, $derived, $seen, '"edge"');
                    }
                    if (isset($grown[$i]) || self::bigger($arg, 4) === null) continue;
                    // much bigger inputs, only while their time (assumed quadratic) fits the budget
                    $grown[$i] = true;
                    try {
                        $t = hrtime(true);
                        (self::$fn)(...$base);
                        $took = hrtime(true) - $t;
                    } catch (Throwable $e) {
                        continue;
                    }
                    // 4x bigger each step, from the short version of the input, up to 4096 items or characters
                    foreach ([4, 16, 64, 256, 512] as $factor) {
                        $big = self::bigger($arg, $factor);
                        if ($big === null || count($derived) >= 100 || $took * 16 > $edgeLeft() / 2) break;
                        $next = $base;
                        $next[$i] = $big;
                        $took = self::attempt($next, $derived, $seen, '"edge"');
                        if ($took === null) break;
                    }
                }
            }
        }
        $edges = count($derived);
        $start = hrtime(true);
        if (self::$fn !== null) {
            foreach (self::$args as $args) {
                if ($args === null) continue;
                foreach ($args as $i => $arg) {
                    foreach (self::variants($arg) as $smaller) {
                        if (count($derived) >= 200 + $edges || hrtime(true) - $start > 3e9) break 3;
                        $next = $args;
                        $next[$i] = $smaller;
                        try {
                            $enc = self::enc($next);
                            if (isset($seen[$enc])) continue;
                            $seen[$enc] = true;
                            $r = (self::$fn)(...$next);
                            $derived[] = '{"args":' . $enc . ',"result":' . self::enc($r) . ',"derived":true}';
                        } catch (Throwable $e) {
                            // outside the function's domain: not a fair test
                        }
                    }
                }
            }
        }
        ksort(self::$kept);
        $lines = array_merge(array_values(self::$kept), $derived);
        file_put_contents(getenv('FAST_ANYTHING_RECORD_OUT'), $lines ? implode("\n", $lines) . "\n" : '');
        file_put_contents(getenv('FAST_ANYTHING_RECORD_META'), json_encode([
            'calls' => self::$calls, 'kept' => count(self::$kept), 'skipped' => self::$skipped,
            'unsupported' => array_keys(self::$why), 'original_ns' => self::$calls ? self::$nanos / self::$calls : 0,
        ]));
    }
}

register_shutdown_function([FastAnythingRec::class, 'save']);
