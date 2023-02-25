#include <vpi_user.h>
#include <svdpi.h>

#include <stdio.h>
#include <string.h>

extern "C" void memtrace_init(
        const char *filename)
{
    printf("memtrace_init: filename=[%s]\n", filename);
}

extern "C" void memtrace_tick(
        unsigned char *trace_read_valid,
        unsigned char trace_read_ready,
        char *trace_read_bits)
{
    printf("tick!\n");
    *trace_read_valid = 1;
    *trace_read_bits = 42;
    return;
}
