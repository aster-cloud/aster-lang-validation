package io.aster.validation.testdata;

/**
 * 测试用策略类：仅声明一个 public static 入口，验证正常加载路径。
 * 限定名形如 {@code io.aster.validation.testdata.single}。
 */
public final class single_fn {

    private single_fn() {
    }

    public static String single(String a) {
        return a;
    }
}
