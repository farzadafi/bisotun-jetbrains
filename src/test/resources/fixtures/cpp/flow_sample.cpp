#include "flow_sample.hpp"

void Pipeline::run() {
    runOne();
    auto pending = jobs_.filter([&](const Job& job) {
        return job.state == JobState::Pending;
    });
    try {
        worker_->execute(pending.front());
    } catch (const std::exception& error) {
        error.what();
    }
}

void Pipeline::runOne() {
    Job job;
    job.id = 1;
    job.state = JobState::Pending;
}

void Worker::execute(const Job& job) {
    (void)job;
}

void Unrelated::runOne() {}

void Unrelated::filter() {}

void Unrelated::execute(const Job& job) {
    (void)job;
}

namespace other {

void Pipeline::runOne() {}

} // namespace other
