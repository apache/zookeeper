How to run unit tests

Usage:

To run all unit tests:

    ant test

To run a specific single unit test:

    ant test -Dtestcase=<test class name>

To run only the quick tests:

    ant test -Dtest.quick=yes

To run a specific test category:

    ant test -Dtest.category=<category name>

To run only the quick tests from a specific test category:

    ant test -Dtest.category=<category name> -Dtest.quick=yes


NOTES:

1) Test categorization is done in configuration files under directory src/java/test/category

    e.g.:
        Func    Functional tests
        Perf    Performance tests
        Slow    Slow tests
        ...
        etc.

2) Flag -Dtest.quick=yes runs the tests from the specified category (or all tests if no category is specified) MINUS the slow tests from category 'Slow'

3) If -Dtestcase=<test class name> is specified then specifying the category or the test.quick flag has no effect

4) It is possible to exclude tests from a category by creating a <category name>.exclude file to enlist those tests/patterns which have to be excluded from the category

    e.g.:
        Commit          Tests to be run before commit
        Commit.exclude  Tests to be excluded from this category

5) Category file <category name> can be omitted if category can be defined as ALL tests MINUS <category name>.exclude
