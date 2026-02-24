package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public final class RT {
    private static final MethodHandle LOOKUP_OR_DEFAULT, LOOKUP_OR_FAIL, REGISTER, INVOKE, TRUTH, LOOKUP_MH;
    static {
        var lookup = MethodHandles.lookup();
        try {
            LOOKUP_OR_DEFAULT = lookup.findVirtual(JSObject.class, "lookupOrDefault", methodType(Object.class, String.class, Object.class));
            LOOKUP_OR_FAIL = lookup.findStatic(RT.class, "lookupOrFail", methodType(Object.class, JSObject.class, String.class));
            REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));

            INVOKE = lookup.findVirtual(JSObject.class, "invoke", methodType(Object.class, Object.class, Object[].class));

            TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

            LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    public static Object bsm_undefined(Lookup lookup, String name, Class<?> type) {
        return UNDEFINED;
    }

    public static Object bsm_const(Lookup lookup, String name, Class<?> type, int constant) {
        return constant;
    }

    @SuppressWarnings("unused") // used by a method handle
    private static Object lookupOrFail(JSObject jsObject, String key) {
        var value = jsObject.lookupOrDefault(key, null);
        if (value == null) {
            throw new Failure("no value for " + key);
        }
        return value;
    }

    public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String variableName) {
        var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
        var globalEnv = classLoader.global();
        // get the LOOKUP_OR_FAIL method handle
        var mh = LOOKUP_OR_FAIL;
        // use the global environment as first argument and the variableName as second argument
        var target = insertArguments(mh, 0, globalEnv, variableName);
        // create a constant callsite
        return new ConstantCallSite(target);
    }

//    public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
//        // get INVOKE method handle
//        var mh = INVOKE;
//        // make it accept an Object (not a JSObject) and objects as other parameters
//        var target = mh.asType(type);
//        // create a constant callsite
//        return new ConstantCallSite(target);
//    }

    public static CallSite bsm_globalcall(Lookup lookup, String name, MethodType type, String variableName) {
        var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
        var globalEnv = classLoader.global();
        return new GlobalEnvInliningCache(type, globalEnv, variableName);
    }

    private static final class GlobalEnvInliningCache extends MutableCallSite {
        private static final MethodHandle SLOW_PATH;

        static {
            var lookup = MethodHandles.lookup();
            try {
                SLOW_PATH = lookup.findVirtual(GlobalEnvInliningCache.class, "slowPath", methodType(MethodHandle.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }

        private final JSObject globalEnv;
        private final String variableName;
        private final MethodHandle fallback;

        private GlobalEnvInliningCache(MethodType type, JSObject globalEnv, String variableName, MethodHandle fallback) {
            this.globalEnv = globalEnv;
            this.variableName = variableName;
            super(type);
            this.fallback = MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this));
            setTarget(fallback);
        }

        @SuppressWarnings("unused")  // called by a MH
        private MethodHandle slowPath() {
            var jsObject = (JSObject) globalEnv.lookupOrDefault(variableName, null);
            if (jsObject == null) {
                throw new Failure("no value for " + variableName);
            }
            var mh = jsObject.methodHandle();
            var target = mh.asType(type());

            var switchPoint = globalEnv.switchPoint();
            var guard = switchPoint.guardWithTest(target, fallback);
            setTarget(guard);

            return target;
        }
    }

    public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
        var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
        var globalEnv = classLoader.global();
        // get the dictionary and get the Fun object corresponding to the id
        var dictionary = classLoader.dictionary();
        var fun = dictionary.lookupAndClear(funId);
        // create the function using ByteCodeRewriter.createFunction(...)
        return ByteCodeRewriter.createFunction(name, fun.parameters(), fun.body(), globalEnv);
    }

    public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {

        var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
        var globalEnv = classLoader.global();
        //get the REGISTER method handle
        var mh = REGISTER;
        // use the global environment as first argument and the functionName as second argument
        var target = insertArguments(mh, 0, globalEnv, functionName);
        // create a constant callsite
        return new ConstantCallSite(target);
    }

    @SuppressWarnings("unused")  // used by a method handle
    private static boolean truth(Object o) {
        return o != null && o != UNDEFINED && o != Boolean.FALSE;
    }
    public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
        throw new UnsupportedOperationException("TODO bsm_truth");
        // get the TRUTH method handle
        // create a constant callsite
    }

    public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
        throw new UnsupportedOperationException("TODO bsm_get");
        // get the LOOKUP_OR_DEFAULT method handle
        // use the fieldName and UNDEFINED as second argument and third argument
        // make it accept an Object (not a JSObject) as first parameter
        // create a constant callsite
    }

    public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
        throw new UnsupportedOperationException("TODO bsm_set");
        // get the REGISTER method handle
        // use the fieldName as second argument
        // make it accept an Object (not a JSObject) as first parameter
        // create a constant callsite
    }

    @SuppressWarnings("unused")  // used by a method handle
    private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
        var function = (JSObject) receiver.lookupOrDefault(fieldName, null);
        if (function == null) {
            throw new Failure("no method " + fieldName);
        }
        return function.methodHandle();
    }

    public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
        throw new UnsupportedOperationException("TODO bsm_methodcall");
        //var combiner = insertArguments(METH_LOOKUP_MH, 1, name).asType(methodType(MethodHandle.class, Object.class));
        //var target = foldArguments(invoker(type), combiner);
        //return new ConstantCallSite(target);
    }

    public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
        return new InliningCache(type, 0, null);
    }

    private static final class InliningCache extends MutableCallSite {
        private static final MethodHandle SLOW_PATH;
        private static final MethodHandle CHECK;

        static {
            var lookup = MethodHandles.lookup();
            try {
                SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
                CHECK = lookup.findStatic(InliningCache.class, "check", methodType(boolean.class, Object.class, Object.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }

        private final int depth;
        private final InliningCache root;

        public InliningCache(MethodType type, int depth, InliningCache root) {
            this.depth = depth;
            super(type);
            this.root = root == null ? this : root;
            setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
        }

        private static boolean check(Object o1, Object o2) {
            return o1 == o2;
        }

        private MethodHandle slowPath(Object qualifier, Object receiver) {
            var jsObject = (JSObject) qualifier;
            var mh = jsObject.methodHandle();

            var isVarargs = mh.isVarargsCollector();
            var target = dropArguments(mh, 0, Object.class);
            if (isVarargs) { //rappeler qu'il s'agit d'un varargs avant
                target = target.withVarargs(isVarargs);
            }
            target = target.asType(type()); //calcule du pointeur de fct

            if (depth == 2) { //stop if more than bi-morphic
                System.out.println("stop if depth");
                setTarget(INVOKE.asType(type()));
                return target;
            }

            var test = insertArguments(CHECK, 1, jsObject);
            var fallback = getTarget();
            var guard = guardWithTest(test, target, new InliningCache(type(), depth + 1, root).dynamicInvoker());
            setTarget(guard);

            return target; //astype remette cela dans le tableau
        }
    }
}
