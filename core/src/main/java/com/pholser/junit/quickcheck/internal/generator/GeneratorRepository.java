/*
 The MIT License

 Copyright (c) 2010-2021 Paul R. Holser, Jr.

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package com.pholser.junit.quickcheck.internal.generator;

import static com.pholser.junit.quickcheck.internal.Items.choose;
import static com.pholser.junit.quickcheck.internal.Reflection.findConstructor;
import static com.pholser.junit.quickcheck.internal.Reflection.findField;
import static com.pholser.junit.quickcheck.internal.Reflection.instantiate;
import static com.pholser.junit.quickcheck.internal.Reflection.isMarkerInterface;
import static com.pholser.junit.quickcheck.internal.Reflection.singleAbstractMethodOf;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.internal.ParameterTypeContext;
import com.pholser.junit.quickcheck.internal.Weighted;
import com.pholser.junit.quickcheck.internal.Zilch;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.javaruntype.type.TypeParameter;

public class GeneratorRepository implements Generators {
    private static final Set<String> NULLABLE_ANNOTATIONS =
        unmodifiableSet(
            Stream.of(
                "javax.annotation.Nullable", // JSR-305
                NullAllowed.class.getCanonicalName())
                .collect(toSet()));

    private final SourceOfRandomness random;
    private final Map<Class<?>, Set<Generator<?>>> generators;

    public GeneratorRepository(SourceOfRandomness random) {
        this(random, new HashMap<>());
    }

    private GeneratorRepository(
        SourceOfRandomness random,
        Map<Class<?>, Set<Generator<?>>> generators) {

        this.random = random;
        this.generators = generators;
    }

    public GeneratorRepository register(Generator<?> source) {
        registerTypes(source);
        return this;
    }

    public GeneratorRepository register(Iterable<Generator<?>> source) {
        for (Generator<?> each : source)
            registerTypes(each);

        return this;
    }

    private void registerTypes(Generator<?> generator) {
        for (Class<?> each : generator.types())
            registerHierarchy(each, generator);
    }

    private void registerHierarchy(Class<?> type, Generator<?> generator) {
        maybeRegisterGeneratorForType(type, generator);

        if (type.getSuperclass() != null)
            registerHierarchy(type.getSuperclass(), generator);
        else if (type.isInterface())
            registerHierarchy(Object.class, generator);

        for (Class<?> each : type.getInterfaces())
            registerHierarchy(each, generator);
    }

    private void maybeRegisterGeneratorForType(
        Class<?> type,
        Generator<?> generator) {

        if (generator.canRegisterAsType(type))
            registerGeneratorForType(type, generator);
    }

    private void registerGeneratorForType(
        Class<?> type,
        Generator<?> generator) {

        Set<Generator<?>> forType =
            generators.computeIfAbsent(type, k -> new LinkedHashSet<>());

        forType.add(generator);
    }

    @Override public Generator<?> field(Class<?> type, String fieldName) {
        return field(findField(type, fieldName));
    }

    @Override public <U> Generator<U> constructor(
        Class<U> type,
        Class<?>... argumentTypes) {

        Constructor<U> constructor = findConstructor(type, argumentTypes);
        Ctor<U> ctor = new Ctor<>(constructor);
        ctor.provide(this);
        ctor.configure(constructor.getAnnotatedReturnType());

        return ctor;
    }

    @Override public <U> Generator<U> fieldsOf(Class<U> type) {
        Fields<U> fields = new Fields<>(type);

        fields.provide(this);
        fields.configure(type);

        return fields;
    }

    @SuppressWarnings("unchecked")
    @Override public <T> Generator<T> type(
        Class<T> type,
        Class<?>... componentTypes) {

        Generator<T> generator =
            (Generator<T>) produceGenerator(
                ParameterTypeContext.forClass(type));
        generator.addComponentGenerators(
            Arrays.stream(componentTypes).map(c -> type(c)).collect(toList()));
        return generator;
    }

    @Override public Generator<?> parameter(Parameter parameter) {
        return produceGenerator(
            ParameterTypeContext.forParameter(parameter).annotate(parameter));
    }

    @Override public Generator<?> field(Field field) {
        return produceGenerator(
            ParameterTypeContext.forField(field).annotate(field));
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    @Override public final <T> Generator<T> oneOf(
        Class<? extends T> first,
        Class<? extends T>... rest) {

        return oneOf(
            type(first),
            Arrays.stream(rest)
                .map(t -> type(t))
                .toArray(Generator[]::new));
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    @Override public final <T> Generator<T> oneOf(
        Generator<? extends T> first,
        Generator<? extends T>... rest) {

        if (rest.length == 0)
            return (Generator<T>) first;

        List<Generator<? extends T>> gens = new ArrayList<>();
        gens.add(first);
        Collections.addAll(gens, rest);

        List<Weighted<Generator<?>>> weightings = gens.stream()
            .map(g -> new Weighted<Generator<?>>(g, 1))
            .collect(toList());

        return (Generator<T>) new CompositeGenerator(weightings);
    }

    @Override public final <T extends Generator<?>> T make(
        Class<T> genType,
        Generator<?>... components) {

        T generator = instantiate(genType);
        generator.provide(this);
        generator.configure(genType);
        generator.addComponentGenerators(asList(components));

        return generator;
    }

    @Override public final Generators withRandom(SourceOfRandomness other) {
        return new GeneratorRepository(other, this.generators);
    }

    public Generator<?> produceGenerator(ParameterTypeContext parameter) {
        Generator<?> generator = generatorFor(parameter);

        if (!isPrimitiveType(parameter.annotatedType().getType())
            && hasNullableAnnotation(parameter.annotatedElement())) {

            generator = new NullableGenerator<>(generator);
        }

        generator.provide(this);
        generator.configure(parameter.annotatedType());
        if (parameter.topLevel())
            generator.configure(parameter.annotatedElement());
        System.out.println("In produce generator");
        System.out.println(generator.toString());
        return generator;
    }

    public Generator<?> generatorFor(ParameterTypeContext parameter) {
         System.out.println("generatorFor");
        System.out.println(parameter.declarerName);
         System.out.println(parameter.getRawClass());
        if (!parameter.explicitGenerators().isEmpty()){
            System.out.println("In compose weighted:");
            System.out.println(composeWeighted(parameter, parameter.explicitGenerators()).toString());
             return composeWeighted(parameter, parameter.explicitGenerators());
        }
           
        if (parameter.isArray()){
            System.out.println("In generatorForArrayType");
            System.out.println(generatorForArrayType(parameter).toString());
            return generatorForArrayType(parameter);
        }
            
        if (parameter.isEnum()){
            System.out.println("In EnumGenerator");
            System.out.println(new EnumGenerator(parameter.getRawClass()).toString());
            return new EnumGenerator(parameter.getRawClass());
        }
            
        System.out.println("In compose");
        System.out.println(compose(parameter, matchingGenerators(parameter)));
        return compose(parameter, matchingGenerators(parameter));
    }

    private Generator<?> generatorForArrayType(
        ParameterTypeContext parameter) {

        ParameterTypeContext component = parameter.arrayComponentContext();
        return new ArrayGenerator(
            component.getRawClass(),
            generatorFor(component));
    }

    private List<Generator<?>> matchingGenerators(
        ParameterTypeContext parameter) {

        List<Generator<?>> matches = new ArrayList<>();

        if (!hasGeneratorsFor(parameter)) {
            maybeAddGeneratorByNamingConvention(parameter, matches);
            maybeAddLambdaGenerator(parameter, matches);
            maybeAddMarkerInterfaceGenerator(parameter, matches);
            System.out.println("In hasGeneratorsFor generators");
            for (Generator<?> generator : matches) {
            
            System.out.println(generator.toString());
        }
        } else {
          
            maybeAddGeneratorsFor(parameter, matches);
              System.out.println("Else In hasGeneratorsFor generators");
            for (Generator<?> generator : matches) {
            
            System.out.println(generator.toString());
        }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot find generator for " + parameter.name()
                + " of type " + parameter.type().getTypeName());
        }

        for (Generator<?> generator : matches) {
            System.out.println("In match generators");
            System.out.println(generator.toString());
        }

        return matches;
    }

    private void maybeAddGeneratorByNamingConvention(
        ParameterTypeContext parameter,
        List<Generator<?>> matches) {

        Class<?> genClass;
        try {
            genClass =
                Class.forName(parameter.getRawClass().getName() + "Gen");
        } catch (ClassNotFoundException noGenObeyingConvention) {
            return;
        }

        if (Generator.class.isAssignableFrom(genClass)) {
            try {
                Generator<?> generator = (Generator<?>) genClass.newInstance();
                if (generator.types().contains(parameter.getRawClass())) {
                    matches.add(generator);
                }
            } catch (IllegalAccessException | InstantiationException e) {
                throw new IllegalStateException(
                    "Cannot instantiate " + genClass.getName()
                        + " using default constructor");
            }
        }
    }

    private void maybeAddLambdaGenerator(
        ParameterTypeContext parameter,
        List<Generator<?>> matches) {

        Method method = singleAbstractMethodOf(parameter.getRawClass());
        if (method != null) {
            ParameterTypeContext returnType =
                parameter.methodReturnTypeContext(method);
            Generator<?> returnTypeGenerator = generatorFor(returnType);

            matches.add(
                new LambdaGenerator<>(
                    parameter.getRawClass(),
                    returnTypeGenerator));
        }
    }

    private void maybeAddMarkerInterfaceGenerator(
        ParameterTypeContext parameter,
        List<Generator<?>> matches) {

        Class<?> rawClass = parameter.getRawClass();
        if (isMarkerInterface(rawClass)) {
            matches.add(
                new MarkerInterfaceGenerator<>(parameter.getRawClass()));
        }
    }

    private void maybeAddGeneratorsFor(
        ParameterTypeContext parameter,
        List<Generator<?>> matches) {
         System.out.println("maybeaddgeneratorsFor");
        System.out.println(parameter.getRawClass());
        List<Generator<?>> candidates = generatorsFor(parameter);
        List<TypeParameter<?>> typeParameters = parameter.getTypeParameters();
        for (Generator<?> generator : candidates) {
            // System.out.println("maybeAddGeneratorsFor");
            System.out.println(parameter.declarerName);
            System.out.println(generator.toString());
        }
        if (typeParameters.isEmpty()) {
            matches.addAll(candidates);
        } else {
            for (Generator<?> each : candidates) {
                if (each.canGenerateForParametersOfTypes(typeParameters))
                    matches.add(each);
            }
        }
    }

    private Generator<?> compose(
        ParameterTypeContext parameter,
        List<Generator<?>> matches) {

        List<Weighted<Generator<?>>> weightings =
            matches.stream()
                .map(g -> new Weighted<Generator<?>>(g, 1))
                .collect(toList());

        return composeWeighted(parameter, weightings);
    }

    private Generator<?> composeWeighted(
        ParameterTypeContext parameter,
        List<Weighted<Generator<?>>> matches) {
            System.out.println("In compose weigghetd fn");
        List<Generator<?>> forComponents = new ArrayList<>();
        List<ParameterTypeContext>contexts = parameter.typeParameterContexts(random);
        for(ParameterTypeContext c :contexts){
            System.out.println(c.getRawClass());
        }
        for (ParameterTypeContext c :contexts){
            System.out.println(c.getRawClass());
            forComponents.add(generatorFor(c));
        }
            

        for (Weighted<Generator<?>> each : matches)
            applyComponentGenerators(each.item, forComponents);

        return matches.size() == 1
            ? matches.get(0).item
            : new CompositeGenerator(matches);
    }

    private void applyComponentGenerators(
        Generator<?> generator,
        List<Generator<?>> componentGenerators) {

        if (!generator.hasComponents())
            return;

        if (componentGenerators.isEmpty()) {
            List<Generator<?>> substitutes = new ArrayList<>();
            Generator<?> zilch =
                generatorFor(
                    ParameterTypeContext.forClass(Zilch.class)
                        .allowMixedTypes(true));
            for (int i = 0; i < generator.numberOfNeededComponents(); ++i) {
                substitutes.add(zilch);
            }

            generator.addComponentGenerators(substitutes);
        } else {
            generator.addComponentGenerators(componentGenerators);
        }
    }

    private List<Generator<?>> generatorsFor(ParameterTypeContext parameter) {
        System.out.println("generatorsFor");
        System.out.println(parameter.getRawClass());
        Set<Generator<?>> matches = generators.get(parameter.getRawClass());
        System.out.println("Matches generators");
        for (Generator<?> generator : matches) {
            System.out.println(generator.toString());
        }
        if (!parameter.allowMixedTypes()) {
            Generator<?> match = choose(matches, random);
            matches = new HashSet<>();
            matches.add(match);
        }

        List<Generator<?>> copies = new ArrayList<>();
        for (Generator<?> each : matches) {
            copies.add(each.copy());
        }
        return copies;
    }

    private boolean hasGeneratorsFor(ParameterTypeContext parameter) {
        return generators.get(parameter.getRawClass()) != null;
    }

    private static boolean isPrimitiveType(Type type) {
        return type instanceof Class<?> && ((Class<?>) type).isPrimitive();
    }

    private static boolean hasNullableAnnotation(AnnotatedElement element) {
        return element != null
            && Arrays.stream(element.getAnnotations())
                .map(Annotation::annotationType)
                .map(Class::getCanonicalName)
                .anyMatch(NULLABLE_ANNOTATIONS::contains);
    }
}
