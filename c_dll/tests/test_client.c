/* Public-API client for the loopback WebSocket regression server. */
#include "aero_ws.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: %s failed (DLL error: %s)\n", \
                __FILE__, __LINE__, #condition, aero_ws_get_error()); \
        return 1; \
    } \
} while (0)

static int invalid_api(void) {
    AeroStates states = {0};
    CHECK(aero_ws_create(NULL, 8765) == NULL);
    CHECK(aero_ws_create("127.0.0.1", 0) == NULL);
    CHECK(aero_ws_connect(NULL, 100) == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_send_raw(NULL, "{}") == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_get_states(NULL, &states, 1) == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_get_states(NULL, NULL, 1) == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_set_joint(NULL, "index_proximal", 45.0f, 200) == AERO_WS_INVALID_PARAM);

    AeroWSHandle handle = aero_ws_create("127.0.0.1", 8765);
    CHECK(handle != NULL);
    CHECK(aero_ws_is_connected(handle) == 0);
    CHECK(aero_ws_send_raw(handle, "{}") == AERO_WS_NOT_CONNECTED);
    CHECK(aero_ws_get_states(handle, &states, 1) == AERO_WS_NOT_CONNECTED);
    CHECK(aero_ws_homing(handle) == AERO_WS_NOT_CONNECTED);
    CHECK(aero_ws_set_joint(handle, "invalid-id", 45.0f, 200) == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_set_joint(handle, "index_proximal", 91.0f, 200) == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_set_joint(handle, "index_proximal", 45.0f, 5001) == AERO_WS_INVALID_PARAM);
    aero_ws_destroy(handle);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "invalid_api") == 0) return invalid_api();
    if (argc != 3) {
        fprintf(stderr, "usage: %s scenario port\n", argv[0]);
        return 2;
    }

    int port = atoi(argv[2]);
    CHECK(port > 0 && port <= 65535);
    AeroWSHandle handle = aero_ws_create("127.0.0.1", port);
    CHECK(handle != NULL);

    const char *scenario = argv[1];
    int result = aero_ws_connect(handle, 1500);
    if (strcmp(scenario, "bad_accept") == 0 ||
        strcmp(scenario, "bad_status") == 0 ||
        strcmp(scenario, "missing_accept") == 0 ||
        strcmp(scenario, "incomplete_handshake") == 0) {
        CHECK(result == AERO_WS_ERROR);
        CHECK(aero_ws_is_connected(handle) == 0);
        CHECK(strstr(aero_ws_get_error(), "Handshake failed") != NULL);
        aero_ws_destroy(handle);
        return 0;
    }

    CHECK(result == AERO_WS_OK);
    CHECK(aero_ws_is_connected(handle) == 1);
    CHECK(aero_ws_set_joint(handle, "bad-id", 20.0f, 100) == AERO_WS_INVALID_PARAM);
    CHECK(aero_ws_send_raw(handle, "{\"type\":\"ping\"}") == AERO_WS_OK);
    CHECK(aero_ws_set_joint(handle, "index_proximal", 45.0f, 200) == AERO_WS_OK);

    AeroStates states = {0};
    result = aero_ws_get_states(handle, &states, 3);
    if (strcmp(scenario, "coalesced") == 0 || strcmp(scenario, "fragmented") == 0) {
        CHECK(result == 1);
        CHECK(states.count == 1 && states.joints != NULL);
        CHECK(strcmp(states.joints[0].joint_id, "index_proximal") == 0);
        CHECK(states.joints[0].angle == 45.0f);
        CHECK(states.joints[0].load == 0.25f);
    } else {
        CHECK(result == AERO_WS_RECV_ERROR);
        CHECK(states.joints == NULL);
    }

    aero_ws_free_states(&states);
    CHECK(states.joints == NULL && states.count == 0);
    aero_ws_destroy(handle);
    return 0;
}
