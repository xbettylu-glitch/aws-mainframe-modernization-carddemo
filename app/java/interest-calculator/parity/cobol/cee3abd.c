/* Parity harness only: stand-in for the Language Environment CEE3ABD
 * service. Ends the run with the requested abend code; cob_stop_run
 * closes open files the way LE termination does on z/OS. The code is a
 * PIC S9(9) BINARY item, which GnuCOBOL stores big-endian. */
#include <stddef.h>
#include <libcob.h>

int CEE3ABD(unsigned char *abcode, unsigned char *timing)
{
    int code = (int)(((unsigned)abcode[0] << 24) | ((unsigned)abcode[1] << 16)
                     | ((unsigned)abcode[2] << 8) | (unsigned)abcode[3]);
    (void)timing;
    cob_stop_run(code);
    return 0;
}
