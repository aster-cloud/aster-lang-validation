package io.aster.validation.testdata;

/**
 * 测试用策略类：声明两个同名 public static 重载，
 * 用于验证 {@code PolicyMetadataLoader} 拒绝歧义重载。
 *
 * <p>类名以 {@code _fn} 结尾以满足编译器约定；限定名形如
 * {@code io.aster.validation.testdata.overloaded}。
 */
public final class overloaded_fn {

    private overloaded_fn() {
    }

    public static String overloaded(String a) {
        return a;
    }

    public static String overloaded(String a, String b) {
        return a + b;
    }
}
