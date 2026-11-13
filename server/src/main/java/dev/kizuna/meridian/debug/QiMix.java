package dev.kizuna.meridian.debug;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 调试补元的属性写法：attribute 写一个属性，mix 写「属性=比例」列表，余下记无属性；内置属性可省 meridian: 前缀。 */
public final class QiMix {
    private static final String BUILTIN_PREFIX = "meridian:";
    private static final char ASSIGN = '=';
    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");
    private static final double SUM_TOLERANCE = 1e-9;
    private static final MathContext FRACTION_DIGITS = new MathContext(6);

    /** 一份属性比例；attribute 是完整的 namespace:path。 */
    public record Share(String attribute, double fraction) { }

    /** mix 补全只替换最后一项：offset 是该项在已输入文字里的起点。 */
    public record Completion(int offset, List<String> candidates) { }

    private QiMix() {
        // 只做文字解析，不持有已登记属性的缓存。
    }

    public static List<Share> single(String text, Collection<String> registered) {
        // attribute 只接一个属性，全部真元记为该属性。
        String name = text.strip();
        if (name.isEmpty() || SEPARATORS.matcher(name).find()) {
            throw new IllegalArgumentException("attribute 只写一个属性；几种属性按比例混合用 mix");
        }
        return List.of(new Share(resolve(name, registered), 1));
    }

    public static List<Share> parse(String text, Collection<String> registered) {
        // 逐项解析「属性=比例」：属性须已登记且不重复，每份比例在 (0, 1]。
        List<Share> shares = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        double total = 0;
        for (String token : tokens(text)) {
            Share share = share(token, registered);
            if (!seen.add(share.attribute())) {
                throw new IllegalArgumentException("属性 " + shortName(share.attribute()) + " 写了两次");
            }
            shares.add(share);
            total += share.fraction();
        }

        // 至少一项；合计超过 1 说明配错了，不替玩家按比例缩放。
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("mix 至少写一项 属性=比例，例如 fire=0.3");
        }
        if (total > 1 + SUM_TOLERANCE) {
            throw new IllegalArgumentException("比例合计 " + plain(total) + " 超过 1；不足 1 的部分自动记无属性");
        }
        return List.copyOf(shares);
    }

    public static String resolve(String name, Collection<String> registered) {
        // 写全 ID 时原样核对；只写路径时先认内置属性，再认唯一同名的附属属性。
        if (name.indexOf(':') >= 0) {
            if (registered.contains(name)) {
                return name;
            }
            throw unknown(name, registered);
        }
        if (registered.contains(BUILTIN_PREFIX + name)) {
            return BUILTIN_PREFIX + name;
        }
        List<String> matches = registered.stream().filter(id -> path(id).equals(name)).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw unknown(name, registered);
        }
        throw new IllegalArgumentException("属性 " + name + " 有多个同名：" + String.join("、", matches) + "，请写全 ID");
    }

    public static String shortName(String id) {
        // 内置属性省去 meridian: 前缀，附属属性保留命名空间，避免同名混淆。
        if (id.startsWith(BUILTIN_PREFIX)) {
            return id.substring(BUILTIN_PREFIX.length());
        }
        return id;
    }

    public static List<String> completeAttribute(String typed, Collection<String> registered) {
        // attribute 后只补一个属性名，按短名或路径前缀匹配。
        String prefix = typed.strip();
        return registered.stream()
                .filter(id -> matches(id, prefix))
                .map(QiMix::shortName)
                .toList();
    }

    public static Completion completeMix(String typed, Collection<String> registered) {
        // 只补最后一项：没写等号时补「属性=」，已写过的属性不再列出；写了等号时补剩下的比例。
        int start = lastTokenStart(typed);
        String token = typed.substring(start);
        List<String> earlier = tokens(typed.substring(0, start));
        int assign = token.indexOf(ASSIGN);
        if (assign < 0) {
            Set<String> used = earlier.stream().map(QiMix::attributePart).collect(Collectors.toSet());
            List<String> names = registered.stream()
                    .filter(id -> matches(id, token) && !used.contains(shortName(id)) && !used.contains(path(id)))
                    .map(id -> shortName(id) + ASSIGN)
                    .toList();
            return new Completion(start, names);
        }

        // 剩余比例按已写各项的合计推算；写错的项不计入，合计已满就不再补。
        double remaining = 1 - earlier.stream().mapToDouble(QiMix::fractionPart).filter(Double::isFinite).sum();
        if (remaining <= SUM_TOLERANCE) {
            return new Completion(start, List.of());
        }
        return new Completion(start, List.of(token.substring(0, assign + 1) + plain(remaining)));
    }

    private static Share share(String token, Collection<String> registered) {
        // 等号前是属性，等号后是比例；比例按普通小数读。
        int assign = token.indexOf(ASSIGN);
        if (assign <= 0) {
            throw new IllegalArgumentException("「" + token + "」要写成 属性=比例，例如 fire=0.3");
        }
        String attribute = resolve(token.substring(0, assign), registered);
        double fraction = fractionPart(token);
        if (!(fraction > 0 && fraction <= 1)) {
            throw new IllegalArgumentException("「" + token + "」的比例要大于 0、不超过 1");
        }
        return new Share(attribute, fraction);
    }

    private static IllegalArgumentException unknown(String name, Collection<String> registered) {
        // 报错时列出全部已登记属性，照着改就行。
        String listed = registered.stream().map(QiMix::shortName).collect(Collectors.joining("、"));
        return new IllegalArgumentException("属性 " + name + " 未登记；已登记：" + listed);
    }

    private static List<String> tokens(String text) {
        // 逗号和空白都能分隔各项，连续分隔符视为一个。
        return Arrays.stream(SEPARATORS.split(text.strip())).filter(token -> !token.isEmpty()).toList();
    }

    private static int lastTokenStart(String typed) {
        // 最后一个逗号或空白之后就是正在输入的那一项。
        int start = 0;
        for (int index = 0; index < typed.length(); index++) {
            char letter = typed.charAt(index);
            if (letter == ',' || Character.isWhitespace(letter)) {
                start = index + 1;
            }
        }
        return start;
    }

    private static String attributePart(String token) {
        // 补全时只需等号前的文字，用来排除已写过的属性。
        int assign = token.indexOf(ASSIGN);
        return assign < 0 ? token : token.substring(0, assign);
    }

    private static double fractionPart(String token) {
        // 读不成数的比例记 NaN，由调用方决定报错还是忽略。
        int assign = token.indexOf(ASSIGN);
        try {
            double fraction = Double.parseDouble(token.substring(assign + 1));
            return Double.isFinite(fraction) ? fraction : Double.NaN;
        } catch (NumberFormatException | IndexOutOfBoundsException malformed) {
            return Double.NaN;
        }
    }

    private static boolean matches(String id, String prefix) {
        // 短名或路径任一以输入开头即匹配，附属属性只打路径也能补出全 ID。
        return shortName(id).startsWith(prefix) || path(id).startsWith(prefix);
    }

    private static String path(String id) {
        // ID 已通过 Framework 语法校验，必有一个冒号。
        return id.substring(id.indexOf(':') + 1);
    }

    private static String plain(double value) {
        // 比例按六位有效数字写出，去掉多余的零，不用科学计数法。
        return new BigDecimal(value).round(FRACTION_DIGITS).stripTrailingZeros().toPlainString();
    }
}
