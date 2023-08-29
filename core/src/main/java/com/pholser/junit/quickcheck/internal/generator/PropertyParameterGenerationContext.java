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

import static java.lang.Math.min;
import static java.lang.String.format;
import static java.math.BigDecimal.ZERO;
import static java.util.Collections.emptyList;

import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.internal.GeometricDistribution;
import com.pholser.junit.quickcheck.internal.ParameterSampler;
import com.pholser.junit.quickcheck.internal.PropertyParameterContext;
import com.pholser.junit.quickcheck.internal.constraint.ConstraintEvaluator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class PropertyParameterGenerationContext
    extends AbstractGenerationStatus {

    private final PropertyParameterContext parameter;
    private final ConstraintEvaluator evaluator;
    private final Generator<?> generator;
    private final int sampleSize;

    private int successfulEvaluations;
    private int discards;
    private int semiAttempts;

    public PropertyParameterGenerationContext(
        PropertyParameterContext parameter,
        GeneratorRepository repository,
        GeometricDistribution distro,
        SourceOfRandomness random,
        ParameterSampler sampler) {

        super(distro, initializeRandomness(parameter, random));

        this.parameter = parameter;
        this.evaluator = new ConstraintEvaluator(parameter.constraint());
        this.generator =
            sampler.decideGenerator(repository, parameter.typeContext());
        this.sampleSize = sampler.sizeFactor(parameter.typeContext());
    }

    private static SourceOfRandomness initializeRandomness(
        PropertyParameterContext p,
        SourceOfRandomness r) {

        if (p.fixedSeed())
            r.setSeed(p.seed());

        return r;
    }

    public Object generate() {
        Object nextValue;
        System.out.println("In generate");
        for (nextValue = generator.generate(random(), this);
            !evaluate(nextValue);) {

            nextValue = generator.generate(random(), this);
        }
System.out.println("after generate");
        return nextValue;
    }

    public List<Object> shrink(Object larger) {
        return generator.canShrink(larger)
            ? new ArrayList<>(generator.shrink(random(), larger))
            : emptyList();
    }

    private boolean evaluate(Object value) {
        System.out.println("Evalue");
        evaluator.bind(value);
        boolean result = evaluator.evaluate();

        if (result) {
            ++successfulEvaluations;
        } else {
            ++discards;
        }

        if (tooManyDiscards()) {
            throw new DiscardRatioExceededException(
                parameter,
                discards,
                successfulEvaluations);
        }
         System.out.println("after Evalue"); 
        return result;
    }

    private boolean tooManyDiscards() {
        if (parameter.discardRatio() == 0)
            return discards > sampleSize();

        return successfulEvaluations == 0
            ? discards > parameter.discardRatio()
            : (discards / successfulEvaluations) >= parameter.discardRatio();
    }

    @Override public int size() {
        return min(super.size(), sampleSize());
    }

    @Override public int attempts() {
        return successfulEvaluations + discards + (semiAttempts / 10);
    }

    @Override public void semiAttempt() {
        ++semiAttempts;
    }

    public long effectiveSeed() {
        return random().seed();
    }

    public int sampleSize() {
        return sampleSize;
    }

    public String name() {
        return parameter.typeContext().name();
    }

    public BigDecimal magnitude(Object value) {
        return generator.canShrink(value)
            ? generator.magnitude(value).abs()
            : ZERO;
    }

    public static class DiscardRatioExceededException
        extends RuntimeException {

        static final String MESSAGE_TEMPLATE =
            "For parameter [%s] with discard ratio [%d],"
                + " %d unsuccessful values and %d successes. Stopping.";

        private static final long serialVersionUID = Long.MIN_VALUE;

        DiscardRatioExceededException(
            PropertyParameterContext parameter,
            int discards,
            int successfulEvaluations) {

            super(format(
                MESSAGE_TEMPLATE,
                parameter.typeContext().name(),
                parameter.discardRatio(),
                discards,
                successfulEvaluations));
        }
    }
}
