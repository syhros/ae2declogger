package com.syhros.ae2declogger.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.syhros.ae2declogger.Ae2Declogger;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Registers {@link DecloggerGameTests} on Minecraft 26.1, where game tests are registry entries instead of annotated
 * methods: every public static method taking a GameTestHelper becomes a test function and a test instance named after
 * it (stalledJobIsReportedAndCompletes becomes ae2declogger:stalled_job_is_reported_and_completes), placed in the
 * empty structure. Dev and CI only, like the rest of this package.
 */
@EventBusSubscriber(modid = Ae2Declogger.MOD_ID)
public final class GameTests261 {

    /** Same budget as the older versions: tests end as soon as their state is reached. */
    private static final int TIMEOUT = 24000;

    private GameTests261() {
    }

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            for (Method method : testMethods()) {
                helper.register(id(method), run(method));
            }
        });
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(Ae2Declogger.id("default"),
                new TestEnvironmentDefinition.AllOf(List.of()));
        for (Method method : testMethods()) {
            Identifier id = id(method);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, Ae2Declogger.id("empty"), TIMEOUT, 0, true)));
        }
    }

    private static List<Method> testMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : DecloggerGameTests.class.getDeclaredMethods()) {
            int mods = method.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && method.getReturnType() == void.class
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == GameTestHelper.class) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static Identifier id(Method method) {
        return Ae2Declogger.id(method.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT));
    }

    private static Consumer<GameTestHelper> run(Method method) {
        return helper -> {
            try {
                method.invoke(null, helper);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
