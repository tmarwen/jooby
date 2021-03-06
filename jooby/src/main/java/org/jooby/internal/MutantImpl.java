/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jooby.internal;

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.BiFunction;

import org.jooby.Body;
import org.jooby.Err;
import org.jooby.MediaType;
import org.jooby.Mutant;
import org.jooby.Status;
import org.jooby.fn.ExSupplier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.TypeConverterBinding;

public class MutantImpl implements Mutant {

  private static final Object[] EMPTY_ARRAY = new Object[0];

  private static Map<Object, BiFunction<String, List<String>, Object>> primiteConverters = new HashMap<>();

  static {
    primiteConverters.put(Boolean.class, MutantImpl::toBoolean);
    primiteConverters.put(Byte.class, MutantImpl::toByte);
    primiteConverters.put(Short.class, MutantImpl::toShort);
    primiteConverters.put(Integer.class, MutantImpl::toInt);
    primiteConverters.put(Long.class, MutantImpl::toLong);
    primiteConverters.put(Float.class, MutantImpl::toFloat);
    primiteConverters.put(Double.class, MutantImpl::toDouble);
    primiteConverters.put(String.class, MutantImpl::stringValue);
  }

  protected final String name;

  protected final List<String> values;

  private Set<TypeConverterBinding> typeConverters;

  private MediaType type = MediaType.all;

  private Injector injector;

  private Charset charset;

  public MutantImpl(final Injector injector, final String name, final List<String> values,
      final MediaType type, final Charset charset) {
    this.injector = requireNonNull(injector, "The injector is required.");
    this.name = requireNonNull(name, "Parameter's name is missing.");
    this.values = values;
    this.type = requireNonNull(type, "The type is required.");
    this.charset = requireNonNull(charset, "The charset is required.");
    this.typeConverters = typeConverters(injector);
  }

  private Set<TypeConverterBinding> typeConverters(final Injector injector) {
    Set<TypeConverterBinding> converters = new LinkedHashSet<>();
    Injector it = injector;
    while (it != null) {
      converters.addAll(it.getTypeConverterBindings());
      it = it.getParent();
    }
    return converters;
  }

  @Override
  public boolean booleanValue() {
    return toBoolean(name, values);
  }

  private static boolean toBoolean(final String name, final List<String> values) {
    failOnEmpty(name, values);
    String value = values.get(0);
    if ("true".equals(value)) {
      return Boolean.TRUE;
    } else if ("false".equals(value)) {
      return Boolean.FALSE;
    }
    throw new IllegalArgumentException("Not a boolean: " + value);
  }

  @Override
  public byte byteValue() {
    return toByte(name, values);
  }

  private static byte toByte(final String name, final List<String> values) {
    failOnEmpty(name, values);
    return Byte.valueOf(values.get(0));
  }

  @Override
  public short shortValue() {
    return toShort(name, values);
  }

  private static short toShort(final String name, final List<String> values) {
    failOnEmpty(name, values);
    return Short.valueOf(values.get(0));
  }

  @Override
  public int intValue() {
    return toInt(name, values);
  }

  private static int toInt(final String name, final List<String> values) {
    failOnEmpty(name, values);
    return Integer.valueOf(values.get(0));
  }

  @Override
  public long longValue() {
    return toLong(name, values);
  }

  private static long toLong(final String name, final List<String> values) {
    failOnEmpty(name, values);
    try {
      return Long.valueOf(values.get(0));
    } catch (NumberFormatException ex) {
      // Is a date?
      try {
        DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        LocalDateTime date = LocalDateTime.parse(values.get(0), formatter);
        Instant instant = date.toInstant(ZoneOffset.UTC);
        return instant.toEpochMilli();
      } catch (DateTimeParseException ignored) {
        throw ex;
      }

    }
  }

  @Override
  public float floatValue() {
    return toFloat(name, values);
  }

  private static float toFloat(final String name, final List<String> values) {
    failOnEmpty(name, values);
    return Float.valueOf(values.get(0));
  }

  @Override
  public double doubleValue() {
    return toDouble(name, values);
  }

  private static double toDouble(final String name, final List<String> values) {
    failOnEmpty(name, values);
    return Double.valueOf(values.get(0));
  }

  @Override
  public <T extends Enum<T>> T enumValue(final Class<T> type) {
    requireNonNull(type, "An enum type is required.");
    failOnEmpty(name, values);
    return Enum.valueOf(type, values.get(0));
  }

  @Override
  public <T> List<T> toList(final Class<T> type) {
    return ImmutableList.copyOf(asArray(type));
  }

  @Override
  public <T> Set<T> toSet(final Class<T> type) {
    return ImmutableSet.copyOf(asArray(type));
  }

  @Override
  public <T extends Comparable<T>> SortedSet<T> toSortedSet(final Class<T> type) {
    T[] array = asArray(type);
    return ImmutableSortedSet.copyOf(array);
  }

  private Object[] asArrayOf(final Class<?> type, final Set<TypeConverterBinding> typeConverters) {
    if (values == null || values.size() == 0) {
      return EMPTY_ARRAY;
    }
    Class<?> componentType = Primitives.wrap(type);
    BiFunction<String, List<String>, Object> converter = converter(type);
    Object array = Array.newInstance(componentType, values.size());
    for (int i = 0; i < values.size(); i++) {
      Object value = converter.apply(name, ImmutableList.of(values.get(i)));
      Array.set(array, i, value);

    }
    return (Object[]) array;
  }

  @SuppressWarnings("unchecked")
  private <T> T[] asArray(final Class<T> type) {
    Object[] array = asArrayOf(type, typeConverters);
    return (T[]) array;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<T> toOptional(final Class<T> type) {
    if (values == null || values.size() == 0) {
      return Optional.empty();
    }
    BiFunction<String, List<String>, Object> converter = converter(type);
    return (Optional<T>) Optional.of(converter.apply(name, values));
  }

  @Override
  public String stringValue() {
    return stringValue(name, values);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T to(final TypeLiteral<T> type) {
    BiFunction<String, List<String>, Object> converter = converter(type);
    return (T) converter.apply(name, values);
  }

  private static String stringValue(final String name, final List<String> values) {
    failOnEmpty(name, values);
    return values.get(0);
  }

  private BiFunction<String, List<String>, Object> converter(final Class<?> type) {
    return converter(TypeLiteral.get(type));
  }

  private BiFunction<String, List<String>, Object> converter(final TypeLiteral<?> literal) {
    Class<?> rawType = literal.getRawType();
    Class<?> wrapType = Primitives.wrap(rawType);
    BiFunction<String, List<String>, Object> converter = primiteConverters.get(wrapType);
    if (converter == null) {
      return complexConverters(literal);
    }
    return converter;
  }

  @SuppressWarnings({"unchecked", "rawtypes" })
  private BiFunction<String, List<String>, Object> complexConverters(
      final TypeLiteral<?> literal) {
    Class<?> rawType = literal.getRawType();
    if (Optional.class.isAssignableFrom(rawType)) {
      return (name, values) -> new MutantImpl(injector, name, values, type, charset)
          .toOptional(classFrom(literal));
    } else if (Enum.class.isAssignableFrom(rawType)) {
      return (name, values) -> new MutantImpl(injector, name, values, type, charset)
          .enumValue((Class<Enum>) rawType);
    } else if (List.class.isAssignableFrom(rawType)) {
      return (name, values) -> new MutantImpl(injector, name, values, type, charset)
          .toList(classFrom(literal));
    } else if (Set.class.isAssignableFrom(rawType)) {
      if (SortedSet.class.isAssignableFrom(rawType)) {
        return (name, values) -> new MutantImpl(injector, name, values, type, charset)
            .toSortedSet((Class) classFrom(literal));
      } else {
        return (name, values) -> new MutantImpl(injector, name, values, type, charset)
            .toSet(classFrom(literal));
      }
    } else {
      if (!type.equals(MediaType.all)) {
        BodyConverterSelector selector = injector.getInstance(BodyConverterSelector.class);
        Optional<Body.Parser> parser = selector.parser(literal, ImmutableList.of(type));
        if (parser.isPresent() && parser.get().canParse(literal)) {

          return (name, values) -> {
            ExSupplier<InputStream> stream = () -> new ByteArrayInputStream(values.get(0).getBytes(
                charset));
            try {
              return parser.get().parse(literal, new BodyReaderImpl(charset, stream));
            } catch (Exception ex) {
              throw new IllegalArgumentException("Can't convert to type: " + rawType.getName(), ex);
            }
          };

        }
      }
      // Guice type converter
      return typeConverters
          .stream()
          .filter(c -> c.getTypeMatcher().matches(literal))
          .findFirst()
          .map(c -> {
            BiFunction<String, List<String>, Object> fn = (name, values) -> c.getTypeConverter()
                .convert(values.get(0), literal);
            return fn;
          })
          .orElseThrow(
              () -> new IllegalArgumentException("Unknown parameter type: " + rawType.getName())
          );
    }
  }

  @Override
  public String toString() {
    if (values == null || values.size() == 0) {
      return "";
    }
    if (values.size() == 1) {
      return values.get(0);
    }
    return values.toString();
  }

  @Override
  public boolean isPresent() {
    return values != null && values.size() > 0;
  }

  private static Class<?> classFrom(final TypeLiteral<?> type) {
    return classFrom(type.getType());
  }

  private static Class<?> classFrom(final Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    }
    ParameterizedType parameterizedType = (ParameterizedType) type;
    Type actualType = parameterizedType.getActualTypeArguments()[0];
    return classFrom(actualType);
  }

  private static void failOnEmpty(final String name, final List<?> values) {
    if (values == null || values.size() == 0) {
      throw new Err(Status.BAD_REQUEST, "Missing value: " + name);
    }
  }
}
