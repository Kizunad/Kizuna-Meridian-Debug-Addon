package dev.kizuna.meridian.debug.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.AddonMessages.ContentSymbolKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** 已登记的真元属性：Framework 内置的六种，加类路径上各公共内容包声明的属性符号；进程内只读一次。 */
final class DebugQiAttributes {
    /** 与 Technique 读取绑定模板内容包的目录相同；每个命名空间一个 <namespace>.pb。 */
    private static final String BUNDLE_DIRECTORY = "META-INF/kizuna_meridian/addons";
    private static final String BUNDLE_SUFFIX = ".pb";
    private static final List<Qi.QiAttribute> BUILTIN = List.of(Qi.QiAttribute.NONE, Qi.QiAttribute.METAL,
            Qi.QiAttribute.WOOD, Qi.QiAttribute.WATER, Qi.QiAttribute.FIRE, Qi.QiAttribute.EARTH);
    private static volatile List<String> registered;

    private DebugQiAttributes() {
        // 模组集合在进程内不变，只缓存一份结果。
    }

    static List<String> registered() {
        // 首次补全或执行时读取；之后直接复用。
        List<String> cached = registered;
        if (cached == null) {
            cached = discover(DebugQiAttributes.class.getClassLoader());
            registered = cached;
        }
        return cached;
    }

    private static List<String> discover(ClassLoader loader) {
        // 正式实例里附属是 Fabric 解到磁盘的嵌套 JAR，开发环境里是类路径上的库；都由模组类加载器列出同名目录。
        var declared = new TreeSet<String>();
        try {
            for (URL directory : Collections.list(loader.getResources(BUNDLE_DIRECTORY))) {
                for (byte[] payload : payloads(directory)) {
                    declared.addAll(declaredAttributes(payload));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("读取公共内容包失败：" + BUNDLE_DIRECTORY, failure);
        }

        // 内置属性按金木水火土排在前面，附属属性按 ID 排序跟在后面。
        List<String> ids = new ArrayList<>();
        BUILTIN.forEach(attribute -> ids.add(attribute.id().value()));
        declared.stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return List.copyOf(ids);
    }

    private static List<byte[]> payloads(URL directory) throws IOException {
        // JAR 按条目名列目录，资源目录按文件列；别的来源说明装配方式变了，直接报错而不是少列属性。
        return switch (directory.getProtocol()) {
            case "jar" -> jarPayloads((JarURLConnection) directory.openConnection());
            case "file" -> filePayloads(directory);
            default -> throw new IllegalStateException("无法列出公共内容包目录：" + directory);
        };
    }

    private static List<byte[]> jarPayloads(JarURLConnection connection) throws IOException {
        // 不走连接缓存，读完自己关闭这份 JarFile，不影响类加载器持有的同一文件。
        connection.setUseCaches(false);
        String prefix = connection.getEntryName() + "/";
        List<byte[]> payloads = new ArrayList<>();
        try (JarFile jar = connection.getJarFile()) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (isBundle(entry.getName(), prefix)) {
                    try (InputStream stream = jar.getInputStream(entry)) {
                        payloads.add(stream.readNBytes(MeridianProtocol.MAX_PAYLOAD_BYTES + 1));
                    }
                }
            }
        }
        return payloads;
    }

    private static List<byte[]> filePayloads(URL directory) throws IOException {
        // 开发环境未打包的资源目录直接按文件列出。
        Path path;
        try {
            path = Path.of(directory.toURI());
        } catch (URISyntaxException failure) {
            throw new IOException("公共内容包目录地址无效：" + directory, failure);
        }
        List<byte[]> payloads = new ArrayList<>();
        try (Stream<Path> files = Files.list(path)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(BUNDLE_SUFFIX)).toList()) {
                payloads.add(Files.readAllBytes(file));
            }
        }
        return payloads;
    }

    private static boolean isBundle(String entryName, String prefix) {
        // 只取目录下一层的 <namespace>.pb，不进子目录。
        return entryName.startsWith(prefix) && entryName.endsWith(BUNDLE_SUFFIX)
                && entryName.indexOf('/', prefix.length()) < 0;
    }

    private static List<String> declaredAttributes(byte[] payload) {
        // 内容包先经 Framework 的字节上限、格式与定义校验，再取其中的属性符号。
        return AddonProtobuf.decodeBundle(payload).getSymbolsList().stream()
                .filter(symbol -> symbol.getKind() == ContentSymbolKind.QI_ATTRIBUTE)
                .map(symbol -> symbol.getId())
                .toList();
    }
}
