package io.aster.validation.metadata;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 策略元数据，缓存策略类及其方法的反射信息。
 */
public class PolicyMetadata {

    private final Class<?> policyClass;
    private final Method method;
    private final MethodHandle methodHandle;
    private final MethodHandle spreadInvoker;
    private final Parameter[] parameters;

    public PolicyMetadata(Class<?> policyClass,
                          Method method,
                          MethodHandle methodHandle,
                          MethodHandle spreadInvoker,
                          Parameter[] parameters) {
        this.policyClass = policyClass;
        this.method = method;
        this.methodHandle = methodHandle;
        this.spreadInvoker = spreadInvoker;
        this.parameters = parameters;
    }

    public Class<?> getPolicyClass() {
        return policyClass;
    }

    public Method getMethod() {
        return method;
    }

    public MethodHandle getMethodHandle() {
        return methodHandle;
    }

    public MethodHandle getSpreadInvoker() {
        return spreadInvoker;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public Object invoke(Object[] arguments) throws Throwable {
        if (spreadInvoker != null) {
            return spreadInvoker.invoke(arguments);
        }
        return methodHandle.invokeWithArguments(arguments);
    }
}
