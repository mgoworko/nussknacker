package pl.touk.nussknacker.engine.spel.internal;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.lang.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class ArrayToListConversionHandler {
    public static final class ConversionAwareMethodsDiscovery {

        public Method[] discover(Class<?> type) {
            if (type.isArray()) {
                return List.class.getMethods();
            }
            return type.getMethods();
        }
    }

    public static final class ConversionAwareMethodInvoker {

        public Object invoke(Method method, Object target, Object[] arguments) throws IllegalAccessException, InvocationTargetException {
            if (target != null && target.getClass().isArray() && method.getDeclaringClass().isAssignableFrom(List.class)) {
                return method.invoke(ArrayToListConversionHandler.convert(target), arguments);
            } else {
                return method.invoke(target, arguments);
            }
        }
    }

    public static final class ArrayToListConverter implements ConditionalGenericConverter {

        private final ConversionService conversionService;


        public ArrayToListConverter(ConversionService conversionService) {
            this.conversionService = conversionService;
        }


        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Set.of(
                new ConvertiblePair(Object[].class, Iterable.class),
                new ConvertiblePair(Object[].class, Collection.class),
                new ConvertiblePair(Object[].class, List.class)
            );
        }

        @Override
        public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
            return ConversionUtils.canConvertElements(
                sourceType.getElementTypeDescriptor(),
                targetType.getElementTypeDescriptor(),
                conversionService
            );
        }

        @Override
        @Nullable
        public Object convert(
            @Nullable Object source,
            TypeDescriptor sourceTypeDescriptor,
            TypeDescriptor targetTypeDescriptor
        ) {
            if (source == null) {
                return null;
            }
            return ArrayToListConversionHandler.convert(source);
        }

    }

    private static List<Object> convert(Object target) {
        if (target.getClass().getComponentType().isPrimitive()) {
            int length = Array.getLength(target);
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object sourceElement = Array.get(target, i);
                result.add(sourceElement);
            }
            return result;
        }
        return Arrays.asList((Object[]) target);
    }
}
