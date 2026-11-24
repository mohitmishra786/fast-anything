/*PACKAGE*/
// fast-anything: records calls to /*ID*/ for one run. Generated; removed when the run ends.
final class FastAnythingRec {
    static boolean inside;
    static final int LIMIT = Integer.parseInt(System.getenv().getOrDefault("FAST_ANYTHING_RECORD_LIMIT", "500"));
    static final java.util.Random RNG = new java.util.Random(0xFA57);
    static final java.util.List<String> kept = new java.util.ArrayList<>();
    static final java.util.List<Object[]> keptArgs = new java.util.ArrayList<>();
    static final java.util.Set<String> unsupported = new java.util.LinkedHashSet<>();
    static long calls, skipped, nanos;
    static boolean mutates;
    static java.util.function.Function<Object[], Object> fn;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(FastAnythingRec::save));
    }

    static synchronized Object record(Object[] args, java.util.function.Function<Object[], Object> f) {
        fn = f;
        inside = true;
        try {
            calls++;
            int slot = kept.size() < LIMIT ? kept.size() : (int) (RNG.nextDouble() * calls);
            String a = null;
            Object[] copy = null;
            if (slot < LIMIT) {
                try {
                    a = Json.enc(args);
                    copy = (Object[]) deepCopy(args);
                } catch (Json.Unsupported e) {
                    skipped++;
                    unsupported.add(e.getMessage());
                }
            }
            long t = System.nanoTime();
            Object r;
            try {
                r = f.apply(args);
            } catch (RuntimeException | Error e) {
                nanos += System.nanoTime() - t;
                if (a != null) store(slot, "{\"args\":" + a + ",\"raises\":" + Json.str(e.getClass().getName()) + "}", null);
                throw e;
            }
            nanos += System.nanoTime() - t;
            if (a != null) {
                try {
                    if (!Json.enc(args).equals(a)) mutates = true;
                } catch (Json.Unsupported e) {
                    // seen before the call: can't happen
                }
                try {
                    store(slot, "{\"args\":" + a + ",\"result\":" + Json.enc(r) + "}", copy);
                } catch (Json.Unsupported e) {
                    skipped++;
                    unsupported.add(e.getMessage());
                }
            }
            return r;
        } finally {
            inside = false;
        }
    }

    static void store(int slot, String line, Object[] args) {
        if (slot < kept.size()) {
            kept.set(slot, line);
            keptArgs.set(slot, args);
        } else {
            kept.add(line);
            keptArgs.add(args);
        }
    }

    static Object deepCopy(Object v) {
        if (v == null) return null;
        if (v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            Object c = java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), n);
            for (int i = 0; i < n; i++) java.lang.reflect.Array.set(c, i, deepCopy(java.lang.reflect.Array.get(v, i)));
            return c;
        }
        if (v instanceof java.util.List<?> l) {
            java.util.List<Object> c = v instanceof java.util.LinkedList ? new java.util.LinkedList<>() : new java.util.ArrayList<>();
            for (Object x : l) c.add(deepCopy(x));
            return c;
        }
        if (v instanceof java.util.Map<?, ?> m) {
            java.util.Map<Object, Object> c = v instanceof java.util.TreeMap ? new java.util.TreeMap<>()
                : v instanceof java.util.LinkedHashMap ? new java.util.LinkedHashMap<>() : new java.util.HashMap<>();
            for (var e : m.entrySet()) c.put(e.getKey(), deepCopy(e.getValue()));
            return c;
        }
        return v;
    }

    static final int[] SLICES = {0, 1, 2, 3, 5, 8, 13, 21, 34, 55};

    static java.util.List<Object> variants(Object v) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        int n = v instanceof String s ? s.codePointCount(0, s.length())
            : v != null && v.getClass().isArray() ? java.lang.reflect.Array.getLength(v)
            : v instanceof java.util.List<?> l ? l.size() : 0;
        if (n < 2) return out;
        for (int len : SLICES) {
            if (len >= n) break;
            for (int rep = 0; rep < (len < 2 ? 1 : 3); rep++) {
                int off = RNG.nextInt(n - len + 1);
                if (v instanceof String s) {
                    out.add(new String(s.codePoints().skip(off).limit(len).toArray(), 0, len));
                } else if (v instanceof java.util.List<?> l) {
                    out.add(deepCopy(new java.util.ArrayList<>(l.subList(off, off + len))));
                } else {
                    Object c = java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), len);
                    System.arraycopy(v, off, c, 0, len);
                    out.add(deepCopy(c));
                }
            }
        }
        return out;
    }

    // an accent, CJK, an emoji with a modifier, a combining mark
    static final String[] NON_ASCII = {"\u00e9", "\u65e5\u672c", "\ud83d\udc4d\ud83c\udffd", "e\u0301"};

    // Inputs where ports and originals tend to part ways, of the same type as the recorded value.
    static java.util.List<Object> edges(Object v) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            for (long x : new long[] {0, 1, -1}) {
                Object e = v instanceof Integer ? (Object) (int) x : v instanceof Long ? (Object) x
                    : v instanceof Short ? (Object) (short) x : (Object) (byte) x;
                if (!e.equals(v)) out.add(e);
            }
        } else if (v instanceof Double d) {
            for (double x : new double[] {0, 1, -1, 1e300, Double.MIN_VALUE}) if (x != d) out.add(x);
        } else if (v instanceof Float f) {
            for (float x : new float[] {0, 1, -1, 1e30f, Float.MIN_VALUE}) if (x != f) out.add(x);
        } else if (v instanceof String s) {
            int mid = s.offsetByCodePoints(0, s.codePointCount(0, s.length()) / 2);
            for (String x : new String[] {s.substring(0, mid) + NON_ASCII[0] + s.substring(mid), s + NON_ASCII[1], NON_ASCII[2] + s, s + NON_ASCII[3], ""}) {
                if (!x.equals(s)) out.add(x);
            }
        } else if (v != null && v.getClass().isArray()) {
            if (java.lang.reflect.Array.getLength(v) == 0) return out;
            out.add(java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), 0));
            for (Object e : edges(java.lang.reflect.Array.get(v, 0)).stream().limit(4).toList()) {
                Object c = deepCopy(v);
                java.lang.reflect.Array.set(c, 0, e);
                out.add(c);
            }
        } else if (v instanceof java.util.List<?> l) {
            if (l.isEmpty()) return out;
            out.add(v instanceof java.util.LinkedList ? new java.util.LinkedList<>() : new java.util.ArrayList<>());
            for (Object e : edges(l.get(0)).stream().limit(4).toList()) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> c = (java.util.List<Object>) deepCopy(l);
                c.set(0, e);
                out.add(c);
            }
        }
        return out;
    }

    // A short version of a recorded value, so edge cases stay cheap when the real inputs are big.
    static Object small(Object v) {
        if (v instanceof String s && s.codePointCount(0, s.length()) > 64) return s.substring(0, s.offsetByCodePoints(0, 64));
        if (v != null && v.getClass().isArray() && java.lang.reflect.Array.getLength(v) > 8) {
            Object c = java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), 8);
            System.arraycopy(v, 0, c, 0, 8);
            return deepCopy(c);
        }
        if (v instanceof java.util.List<?> l && l.size() > 8) return deepCopy(new java.util.ArrayList<>(l.subList(0, 8)));
        return v;
    }

    // The value repeated factor times (null past 4096 items or characters).
    static Object bigger(Object v, int factor) {
        if (v instanceof String s) {
            int n = s.codePointCount(0, s.length());
            return n == 0 || n * factor > 4096 ? null : s.repeat(factor);
        }
        if (v != null && v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            if (n == 0 || n * factor > 4096) return null;
            Object c = java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), n * factor);
            for (int k = 0; k < factor; k++) System.arraycopy(deepCopy(v), 0, c, k * n, n);
            return c;
        }
        if (v instanceof java.util.List<?> l) {
            if (l.isEmpty() || l.size() * factor > 4096) return null;
            java.util.List<Object> c = new java.util.ArrayList<>(l.size() * factor);
            for (int k = 0; k < factor; k++) c.addAll((java.util.List<?>) deepCopy(l));
            return c;
        }
        return null;
    }

    // How long the original took on args, or -1 if it threw (outside its domain: not a fair test).
    static long attempt(Object[] args, java.util.List<String> derived, java.util.Set<String> seen, String tag) {
        try {
            String a = Json.enc(args);
            if (!seen.add(a)) return -1;
            long t = System.nanoTime();
            Object r = fn.apply((Object[]) deepCopy(args));
            long took = System.nanoTime() - t;
            derived.add("{\"args\":" + a + ",\"result\":" + Json.enc(r) + ",\"derived\":" + tag + "}");
            return took;
        } catch (Throwable e) {
            return -1;
        }
    }

    static synchronized void save() {
        inside = true;
        java.util.List<String> derived = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Edge cases first, from a sample of recorded calls: special numbers, non-ASCII text, empty and
        // much bigger inputs. The original answers them; inputs it throws on are skipped.
        long start = System.nanoTime();
        java.util.function.LongSupplier edgeLeft = () -> 1_500_000_000L - (System.nanoTime() - start);
        java.util.Set<Integer> grown = new java.util.HashSet<>();
        int sampled = 0;
        sampling:
        for (Object[] args : keptArgs) {
            if (args == null || fn == null) continue;
            if (++sampled > 20) break;
            Object[] base = new Object[args.length];
            for (int i = 0; i < args.length; i++) base[i] = small(args[i]);
            for (int i = 0; i < args.length; i++) {
                for (Object e : edges(base[i])) {
                    if (derived.size() >= 100 || edgeLeft.getAsLong() <= 0) break sampling;
                    Object[] next = (Object[]) deepCopy(base);
                    next[i] = e;
                    attempt(next, derived, seen, "\"edge\"");
                }
                if (grown.contains(i) || bigger(base[i], 4) == null) continue;
                // much bigger inputs, only while their time (assumed quadratic) fits the budget
                grown.add(i);
                long took;
                try {
                    long t = System.nanoTime();
                    fn.apply((Object[]) deepCopy(base));
                    took = System.nanoTime() - t;
                } catch (Throwable e) {
                    continue;
                }
                // 4x bigger each step, from the short version of the input, up to 4096 items or characters
                for (int factor : new int[] {4, 16, 64, 256, 512}) {
                    Object big = bigger(base[i], factor);
                    if (big == null || derived.size() >= 100 || took * 16 > edgeLeft.getAsLong() / 2) break;
                    Object[] next = (Object[]) deepCopy(base);
                    next[i] = big;
                    if ((took = attempt(next, derived, seen, "\"edge\"")) < 0) break;
                }
            }
        }
        int edgeCount = derived.size();
        long derivedStart = System.nanoTime();
        outer:
        for (Object[] args : keptArgs) {
            if (args == null || fn == null) continue;
            for (int i = 0; i < args.length; i++) {
                for (Object smaller : variants(args[i])) {
                    if (derived.size() >= 200 + edgeCount || System.nanoTime() - derivedStart > 3_000_000_000L) break outer;
                    Object[] next = (Object[]) deepCopy(args);
                    next[i] = smaller;
                    try {
                        String a = Json.enc(next);
                        if (!seen.add(a)) continue;
                        Object r = fn.apply(next);
                        derived.add("{\"args\":" + a + ",\"result\":" + Json.enc(r) + ",\"derived\":true}");
                    } catch (Throwable e) {
                        // outside the function's domain: not a fair test
                    }
                }
            }
        }
        try {
            StringBuilder b = new StringBuilder();
            for (String line : kept) b.append(line).append('\n');
            for (String line : derived) b.append(line).append('\n');
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("FAST_ANYTHING_RECORD_OUT")), b, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder u = new StringBuilder("[");
            for (String s : unsupported) u.append(u.length() > 1 ? "," : "").append(Json.str(s));
            String meta = "{\"calls\":" + calls + ",\"kept\":" + kept.size() + ",\"skipped\":" + skipped + ",\"unsupported\":" + u + "],\"mutates\":" + mutates
                + ",\"original_ns\":" + (calls > 0 ? (double) nanos / calls : 0) + "}";
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("FAST_ANYTHING_RECORD_META")), meta, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

/*JSON*/
}
