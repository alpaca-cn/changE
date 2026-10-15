package com.change.calc;

/** 一份营养值。单位：kcal 与克。 */
public record Nutrition(double kcal, double protein, double carb,
                        double fat, double fiber, double sodium) {
}
