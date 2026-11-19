#include "engine.hpp"
#include <vector>
#include <iostream>

namespace core {

static int compute(int value) {
    int total = value + 1;
    return total;
}

void Engine::start(int retries) {
    int attempts = 0;
    auto retry = [this](int step) {
        this->start(step);
        return step + 1;
    };
    if (retries > 0) {
        for (int i = 0; i < retries; ++i) {
            attempts += compute(i);
        }
    } else {
        while (attempts < 3) {
            attempts++;
        }
    }
    switch (attempts) {
        case 0:
            break;
        default:
            break;
    }
    do {
        attempts--;
    } while (attempts > 0);
    for (const auto& item : values_) {
        use(item);
    }
    Engine* engine = new Engine(7);
    engine->start(2);
    try {
        throw std::runtime_error("boom");
    } catch (const std::exception& error) {
        attempts = 0;
    }
    retry(attempts);
}

} // namespace core
