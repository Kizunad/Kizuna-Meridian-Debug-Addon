package dev.kizuna.meridian.debug;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 身体快照附属进度的通用透视镜展示记录。 */
public record AddonProgressDisplay(
        String key,
        String namespace,
        String path,
        String translationKey,
        String value) {

    private static final String KEY_SEPARATOR = ":";
    private static final String TRANSLATION_PREFIX = "lens.addon.";

    public static List<AddonProgressDisplay> from(Map<String, Any> progress) {
        return progress.entrySet().stream()
                .map(AddonProgressDisplay::fromEntry)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(AddonProgressDisplay::key))
                .toList();
    }

    private static Optional<AddonProgressDisplay> fromEntry(Map.Entry<String, Any> entry) {
        String key = entry.getKey();
        int separator = key.indexOf(KEY_SEPARATOR);
        if (!validKey(key, separator)) {
            return Optional.empty();
        }
        Optional<String> displayValue = simpleValue(entry.getValue());
        if (displayValue.isEmpty()) {
            return Optional.empty();
        }
        String namespace = key.substring(0, separator);
        String path = key.substring(separator + KEY_SEPARATOR.length());
        String translationKey = TRANSLATION_PREFIX + namespace + "." + path;
        return Optional.of(new AddonProgressDisplay(key, namespace, path, translationKey,
                displayValue.get()));
    }

    private static boolean validKey(String key, int separator) {
        return separator > 0
                && separator == key.lastIndexOf(KEY_SEPARATOR)
                && separator < key.length() - KEY_SEPARATOR.length()
                && key.chars().noneMatch(Character::isWhitespace);
    }

    private static Optional<String> simpleValue(Any value) {
        try {
            if (value.is(StringValue.class)) {
                return Optional.of(value.unpack(StringValue.class).getValue());
            }
            if (value.is(Int64Value.class)) {
                return Optional.of(Long.toString(value.unpack(Int64Value.class).getValue()));
            }
            if (value.is(UInt64Value.class)) {
                return Optional.of(Long.toUnsignedString(value.unpack(UInt64Value.class).getValue()));
            }
            if (value.is(Int32Value.class)) {
                return Optional.of(Integer.toString(value.unpack(Int32Value.class).getValue()));
            }
            if (value.is(UInt32Value.class)) {
                return Optional.of(Long.toUnsignedString(value.unpack(UInt32Value.class).getValue()));
            }
            if (value.is(BoolValue.class)) {
                return Optional.of(Boolean.toString(value.unpack(BoolValue.class).getValue()));
            }
            if (value.is(DoubleValue.class)) {
                return Optional.of(Double.toString(value.unpack(DoubleValue.class).getValue()));
            }
            if (value.is(FloatValue.class)) {
                return Optional.of(Float.toString(value.unpack(FloatValue.class).getValue()));
            }
        } catch (InvalidProtocolBufferException invalid) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
