#pragma once

#include <string>

namespace core {

class Base {
public:
    virtual ~Base() = default;
    virtual void onStart() {}
};

template <typename T>
class Engine : public Base {
public:
    explicit Engine(int id);
    ~Engine();

    void start(int retries);
    T value() const;

private:
    int id_;
    T value_;
    std::string name_;
};

} // namespace core
