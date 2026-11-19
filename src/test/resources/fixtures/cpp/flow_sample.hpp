#pragma once

#include <functional>
#include <memory>
#include <stdexcept>
#include <vector>

enum class JobState { Pending, Running, Done };

struct Job {
    int id;
    JobState state;
};

template <typename T>
class Repository {
public:
    std::vector<T> filter(const std::function<bool(const T&)>& predicate) const {
        std::vector<T> selected;
        for (const auto& item : items_) {
            if (predicate(item)) {
                selected.push_back(item);
            }
        }
        return selected;
    }

    void add(const T& item);

private:
    std::vector<T> items_;
};

class Worker {
public:
    void execute(const Job& job);
};

class Pipeline {
public:
    void run();
    void runOne();

private:
    Repository<Job> jobs_;
    std::unique_ptr<Worker> worker_;
};

class Unrelated {
public:
    void runOne();
    void filter();
    void execute(const Job& job);
};

namespace other {

class Pipeline {
public:
    void runOne();
};

} // namespace other
