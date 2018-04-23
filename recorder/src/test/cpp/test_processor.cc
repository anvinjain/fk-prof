#include <thread>
#include <vector>
#include <iostream>
#include <utility>
#include <cstdint>
#include <chrono>
#include "fixtures.hh"
#include "test.hh"
#include "../../main/cpp/globals.hh"
#include "processor.hh"

class TestProcess : public Process {
public:
    std::vector<int> &vec;
    Time::msec itvl;
    int id;

    TestProcess(std::vector<int> &_vec, Time::msec _itvl, int _id)
        : vec(_vec), itvl(_itvl), id(_id) {
    }

    void run() override {
        vec.push_back(id);
    }

    Time::msec run_itvl() override {
        return itvl;
    };

    void stop() override {
        vec.push_back(-id);
    };
};

TEST(Processor___multiple_periodic_processes) {
    TestEnv _;

    std::vector<int> events;
    ProcessPtr p1 = std::make_shared<TestProcess>(events, Time::msec(250), 1),
               p2 = std::make_shared<TestProcess>(events, Time::msec(500), 2),
               p3 = std::make_shared<TestProcess>(events, Time::msec(1000), 3);

    Processor processor(nullptr);
    Processes processes = Processes{p2, p1, p3};

    try {
        processor.start(nullptr, processes);
    } catch(std::exception& e) {}

    auto thd = std::thread([&]() { processor.run(); });

    std::this_thread::sleep_for(Time::msec(1050));

    processor.stop();

    thd.join();

    // five times{2, 1, 3}, then stop marker {-2, -1, -3}
    std::vector<int> expected{2, 1, 3, 2, 1, 3, 2, 1, 3, 2, 1, 3, 2, 1, 3, -2, -1, -3};
    CHECK_ARRAY_EQUAL(expected, events, 18);
}

TEST(Processor___mix_processes) {
    TestEnv _;

    std::vector<int> events;
    ProcessPtr p1 = std::make_shared<TestProcess>(events, Time::msec(500), 1),
               p2 = std::make_shared<TestProcess>(events, Time::msec(100), 2),
               p3 = std::make_shared<TestProcess>(events, Process::run_itvl_ignore, 3);

    Processor processor(nullptr);
    Processes processes = Processes{p2, p1, p3};

    try {
        processor.start(nullptr, processes);
    } catch(std::exception& e) {}
    
    auto thd = std::thread([&]() { processor.run(); });

    std::this_thread::sleep_for(Time::msec(550));
    processor.stop();
    thd.join();

    // six times{2, 1, 3}, then stop marker {-2, -1, -3}
    std::vector<int> expected{2, 1, 3, 2, 1, 3, 2, 1, 3, 2, 1, 3, 2, 1, 3, 2, 1, 3, -2, -1, -3};
    CHECK_ARRAY_EQUAL(expected, events, 21);
}