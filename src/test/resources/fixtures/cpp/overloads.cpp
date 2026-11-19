#include "overloads.hpp"

int Calculator::compute(int value) {
    return value + 1;
}

int Calculator::compute(int value, int extra) {
    return value + extra;
}

void runCalculator() {
    Calculator calculator;
    calculator.compute(2);
    calculator.compute(2, 3);
    unknownCall();
}
