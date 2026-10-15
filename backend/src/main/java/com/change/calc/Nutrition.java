package com.change.calc;

/** 一份营养值。热量与三大营养素、纤维单位为克（kcal 除外）；钠为毫克。 */
public record Nutrition(double kcal, double protein, double carb,
                        double fat, double fiber, double sodium) {
}
