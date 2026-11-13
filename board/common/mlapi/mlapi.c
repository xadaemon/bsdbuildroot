/*-
 * SPDX-License-Identifier: BSD-2-Clause
 *
 * Copyright (c) 2026 Matheus Xavier
 *
 * mlapi(1): OpenComputers II's mid-level API from the shell. The image has no
 * C compiler, so this is what scripts use to list the devices, call their
 * functions and wait for their events. See mlapi.h and OC2's docs/mlapi.md.
 */

#include <errno.h>
#include <limits.h>
#include <signal.h>

#include "mlapi.h"

static volatile sig_atomic_t stop;

static void
usage(void)
{
	fprintf(stderr,
	    "usage: mlapi list\n"
	    "       mlapi find NAME...\n"
	    "       mlapi call [-t] DEVICE FUNCTION [ARGUMENT...]\n"
	    "       mlapi events [-n COUNT]\n"
	    "\n"
	    "DEVICE is an index, as list prints it, or a name.\n"
	    "An ARGUMENT is a byte, 0 to 255, or s:TEXT for the bytes of TEXT.\n"
	    "Results are printed as bytes, or with -t as text.\n"
	    "Events are printed as: DEVICE CODE VALUE.\n");
	exit(2);
}

static long
number(const char *text, long max, const char *what)
{
	char *end;
	long value;

	errno = 0;
	value = strtol(text, &end, 0);
	if (errno != 0 || end == text || *end != '\0' || value < 0 ||
	    value > max) {
		fprintf(stderr, "mlapi: %s: not a %s\n", text, what);
		exit(2);
	}
	return (value);
}

static const char *
error_text(int code)
{
	switch (code) {
	case OCENOD:
		return ("no such device");
	case OCENOF:
		return ("no such function");
	case OCEARG:
		return ("invalid arguments");
	case OCEINT:
		return ("internal device error");
	default:
		return ("unknown error");
	}
}

/* Prints the names of the selected entry: tab, name, tab, aliases. */
static void
print_names(void)
{
	int c, names;

	mlapi_io[BNAM] = 0;
	names = 0;
	while ((c = mlapi_io[BNAM]) != 0) {
		fputs(names++ < 2 ? "\t" : ", ", stdout);
		do {
			putchar(c);
		} while ((c = mlapi_io[BNAM]) != 0);
	}
	putchar('\n');
}

static int
cmd_list(int argc, char **argv)
{
	int count, i;

	(void)argv;
	if (argc != 1)
		usage();
	count = mlapi_io[BCNT];
	for (i = 0; i < count; i++) {
		mlapi_io[BSEL] = i;
		if (mlapi_io[BCLS] != CLSOC)
			continue;
		printf("%d", mlapi_io[BATT]);
		print_names();
	}
	return (0);
}

static int
cmd_find(int argc, char **argv)
{
	int device;

	if (argc < 2)
		usage();
	device = mlapi_find_all(0, (const char *const *)argv + 1);
	if (device < 0)
		return (1);
	printf("%d\n", device);
	return (0);
}

static int
device_arg(const char *text)
{
	const char *names[2] = { text, NULL };
	int device;

	if (*text >= '0' && *text <= '9')
		return (number(text, 0xfe, "device index"));
	device = mlapi_find_all(0, names);
	if (device < 0) {
		fprintf(stderr, "mlapi: no device named %s\n", text);
		exit(1);
	}
	return (device);
}

static int
cmd_call(int argc, char **argv)
{
	unsigned char arguments[MLAPI_MAX_DATA], results[MLAPI_MAX_DATA];
	int as_text, ch, count, device, function, i, n;
	size_t len;

	as_text = 0;
	while ((ch = getopt(argc, argv, "t")) != -1) {
		switch (ch) {
		case 't':
			as_text = 1;
			break;
		default:
			usage();
		}
	}
	argc -= optind;
	argv += optind;
	if (argc < 2)
		usage();

	device = device_arg(argv[0]);
	function = number(argv[1], 0xfe, "function code");
	n = 0;
	for (i = 2; i < argc; i++) {
		if (strncmp(argv[i], "s:", 2) == 0) {
			len = strlen(argv[i] + 2);
			if (n + len > sizeof(arguments))
				goto too_long;
			memcpy(arguments + n, argv[i] + 2, len);
			n += len;
		} else {
			if (n == sizeof(arguments))
				goto too_long;
			arguments[n++] = number(argv[i], 0xff, "byte");
		}
	}

	count = mlapi_call(device, function, arguments, n, results,
	    sizeof(results));
	if (count < 0) {
		fprintf(stderr, "mlapi: %s\n", error_text(-count));
		return (1);
	}
	if (count > (int)sizeof(results))
		count = sizeof(results);
	if (as_text) {
		/* A trailing zero byte ends the text. */
		if (count > 0 && results[count - 1] == 0)
			count--;
		fwrite(results, 1, count, stdout);
		putchar('\n');
	} else {
		for (i = 0; i < count; i++)
			printf("%s%d", i > 0 ? " " : "", results[i]);
		putchar('\n');
	}
	return (0);

too_long:
	fprintf(stderr, "mlapi: more than %d argument bytes\n",
	    MLAPI_MAX_DATA);
	return (2);
}

static void
on_signal(int sig)
{
	(void)sig;
	stop = 1;
}

static int
cmd_events(int argc, char **argv)
{
	struct sigaction sa;
	mlapi_event_t event;
	long left;
	int ch, status;

	left = -1;
	while ((ch = getopt(argc, argv, "n:")) != -1) {
		switch (ch) {
		case 'n':
			left = number(optarg, LONG_MAX, "count");
			break;
		default:
			usage();
		}
	}
	if (optind != argc)
		usage();

	/* No SA_RESTART: a signal ends the wait, and the events are turned
	 * off again on the way out. */
	memset(&sa, 0, sizeof(sa));
	sa.sa_handler = on_signal;
	sigemptyset(&sa.sa_mask);
	sigaction(SIGINT, &sa, NULL);
	sigaction(SIGTERM, &sa, NULL);
	sigaction(SIGHUP, &sa, NULL);
	signal(SIGPIPE, SIG_IGN);

	status = 0;
	mlapi_events_enable();
	while (!stop && left != 0) {
		if (mlapi_io[OCEVC] & OCEVO) {
			fprintf(stderr, "mlapi: events were dropped, "
			    "the queue was full\n");
			/* Any write clears it; keep what is enabled. */
			mlapi_io[OCEVC] = mlapi_io[OCEVC] & (OCEVQ | OCEVI);
		}
		while (left != 0 && mlapi_event_read(&event) == 0) {
			if (printf("%d %d %d\n", event.device, event.code,
			    event.value) < 0 || fflush(stdout) != 0) {
				stop = 1;
				break;
			}
			if (left > 0)
				left--;
		}
		if (stop || left == 0)
			break;
		if (mlapi_events_wait() < 0 && errno != EINTR) {
			perror("mlapi: waiting for events");
			status = 1;
			break;
		}
	}
	mlapi_events_disable();
	return (status);
}

int
main(int argc, char **argv)
{
	if (argc < 2)
		usage();
	if (mlapi_open() < 0) {
		if (errno == ENOENT)
			fprintf(stderr, "mlapi: this computer has no "
			    "mid-level API\n");
		else
			fprintf(stderr, "mlapi: %s: %s\n", mlapi_failed,
			    strerror(errno));
		return (1);
	}

	argc--;
	argv++;
	if (strcmp(argv[0], "list") == 0)
		return (cmd_list(argc, argv));
	if (strcmp(argv[0], "find") == 0)
		return (cmd_find(argc, argv));
	if (strcmp(argv[0], "call") == 0)
		return (cmd_call(argc, argv));
	if (strcmp(argv[0], "events") == 0)
		return (cmd_events(argc, argv));
	usage();
}
