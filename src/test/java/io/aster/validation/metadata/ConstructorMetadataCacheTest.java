package io.aster.validation.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for follow-up issue #6 task 1: constructor -> field index mapping must be
 * reliable. Records (with stable component order) map correctly; non-record classes
 * compiled without {@code -parameters} must NOT silently guess a mapping by field
 * declaration order.
 */
class ConstructorMetadataCacheTest {

    @Test
    @DisplayName("Record: 组件顺序可靠,映射正确建立,且 fallback=false")
    void recordMapsCorrectly() {
        ConstructorMetadataCache cache = new ConstructorMetadataCache();
        ConstructorMetadata metadata = cache.getConstructorMetadata(SampleRecord.class);

        Map<String, Integer> mapping = metadata.getFieldNameToParameterIndex();
        assertThat(mapping).containsEntry("code", 0).containsEntry("level", 1);
        assertThat(metadata.isFallbackToFieldOrder()).isFalse();
    }

    @Test
    @DisplayName("普通类 + -parameters: 按参数名建立可靠映射, fallback=false")
    void regularClassWithParameterNamesMapsCorrectly() {
        // This test class is compiled by the project build with -parameters, so
        // parameter names are present here.
        ConstructorMetadataCache cache = new ConstructorMetadataCache();
        ConstructorMetadata metadata = cache.getConstructorMetadata(SamplePojo.class);

        Map<String, Integer> mapping = metadata.getFieldNameToParameterIndex();
        assertThat(mapping).containsKeys("name", "age");
        assertThat(metadata.isFallbackToFieldOrder()).isFalse();
    }

    @Test
    @DisplayName("非 record 且无 -parameters (SKIP 默认): 不猜测,映射为空, fallback=true")
    void nonRecordWithoutParametersSkipsMapping() throws Exception {
        Class<?> pojo = compileWithoutParameters();

        ConstructorMetadataCache cache = new ConstructorMetadataCache();
        ConstructorMetadata metadata = cache.getConstructorMetadata(pojo);

        // No silent guess: mapping is unavailable (empty) and the unreliable state
        // is surfaced to callers via fallbackToFieldOrder.
        assertThat(metadata.getFieldNameToParameterIndex()).isEmpty();
        assertThat(metadata.isFallbackToFieldOrder()).isTrue();
    }

    @Test
    @DisplayName("非 record 且无 -parameters (THROW 策略): 抛出清晰异常,不静默猜测")
    void nonRecordWithoutParametersThrowsWhenConfigured() throws Exception {
        Class<?> pojo = compileWithoutParameters();

        ConstructorMetadataCache cache = new ConstructorMetadataCache(
            ConstructorMetadataCache.UnreliableMappingPolicy.THROW);

        assertThatThrownBy(() -> cache.getConstructorMetadata(pojo))
            .isInstanceOf(UnreliableConstructorMappingException.class)
            .hasMessageContaining(pojo.getName());
    }

    @Test
    @DisplayName("getFields() 返回防御性拷贝: 调用方置空不污染进程级缓存")
    void getFieldsReturnsDefensiveCopy() {
        ConstructorMetadataCache cache = new ConstructorMetadataCache();
        ConstructorMetadata metadata = cache.getConstructorMetadata(SamplePojo.class);

        java.lang.reflect.Field[] first = metadata.getFields();
        assertThat(first).isNotEmpty();
        first[0] = null; // attempt to corrupt the shared cached array

        // A fresh read from the same cached metadata must be unaffected.
        assertThat(cache.getConstructorMetadata(SamplePojo.class).getFields()).doesNotContainNull();
        assertThat(metadata.getFields()).doesNotContainNull();
    }

    @Test
    @DisplayName("getParameters() 返回防御性拷贝: 调用方置空不污染进程级缓存")
    void getParametersReturnsDefensiveCopy() {
        ConstructorMetadataCache cache = new ConstructorMetadataCache();
        ConstructorMetadata metadata = cache.getConstructorMetadata(SamplePojo.class);

        java.lang.reflect.Parameter[] first = metadata.getParameters();
        assertThat(first).isNotEmpty();
        first[0] = null;

        assertThat(cache.getConstructorMetadata(SamplePojo.class).getParameters()).doesNotContainNull();
        assertThat(metadata.getParameters()).doesNotContainNull();
    }

    /**
     * Compiles a non-record POJO WITHOUT the {@code -parameters} flag so that its
     * constructor parameter names are not retained, then loads it. This reproduces
     * the real-world condition the mapping logic must not silently guess around.
     */
    private static Class<?> compileWithoutParameters() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "system Java compiler must be available (run on a JDK)");

        String className = "io.aster.validation.testgen.NoParamPojo";
        String source = """
            package io.aster.validation.testgen;
            public class NoParamPojo {
                private final String alpha;
                private final int beta;
                public NoParamPojo(String alpha, int beta) {
                    this.alpha = alpha;
                    this.beta = beta;
                }
            }
            """;

        InMemoryClassFile classFile = new InMemoryClassFile(className);
        InMemoryFileManager fileManager =
            new InMemoryFileManager(compiler.getStandardFileManager(null, null, null), classFile);
        JavaFileObject sourceFile = new InMemorySource(className, source);

        // NOTE: deliberately NOT passing "-parameters" so names are dropped.
        boolean ok = compiler.getTask(null, fileManager, null, null, null, List.of(sourceFile)).call();
        assertThat(ok).as("in-memory compilation should succeed").isTrue();

        ClassLoader loader = new ClassLoader(ConstructorMetadataCacheTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(className)) {
                    byte[] bytes = classFile.getBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };
        return Class.forName(className, true, loader);
    }

    // --- in-memory javac plumbing ---

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class InMemoryClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        InMemoryClassFile(String className) {
            super(URI.create("bytes:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
        }

        @Override
        public java.io.OutputStream openOutputStream() {
            return out;
        }

        byte[] getBytes() {
            return out.toByteArray();
        }
    }

    private static final class InMemoryFileManager
            extends javax.tools.ForwardingJavaFileManager<javax.tools.StandardJavaFileManager> {
        private final InMemoryClassFile classFile;

        InMemoryFileManager(javax.tools.StandardJavaFileManager delegate, InMemoryClassFile classFile) {
            super(delegate);
            this.classFile = classFile;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
            return classFile;
        }
    }

    // --- fixtures ---

    public record SampleRecord(String code, Integer level) {
    }

    public static class SamplePojo {
        private final String name;
        private final int age;

        public SamplePojo(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }
}
