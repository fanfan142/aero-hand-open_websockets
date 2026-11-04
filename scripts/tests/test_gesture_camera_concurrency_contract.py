from __future__ import annotations

import re
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATH = REPO_ROOT / (
    "android/app/src/main/java/com/aerohand/gesture/GestureCameraService.kt"
)


def source() -> str:
    return SOURCE_PATH.read_text(encoding="utf-8")


def function_body(kotlin_source: str, function_name: str) -> str:
    match = re.search(rf"\bfun\s+{re.escape(function_name)}\s*\(", kotlin_source)
    assert match is not None, f"missing function: {function_name}"
    body_start = kotlin_source.find("{", match.end())
    assert body_start >= 0, f"missing body: {function_name}"

    depth = 0
    for index in range(body_start, len(kotlin_source)):
        token = kotlin_source[index]
        if token == "{":
            depth += 1
        elif token == "}":
            depth -= 1
            if depth == 0:
                return kotlin_source[body_start + 1 : index]
    raise AssertionError(f"unterminated body: {function_name}")


def test_heavy_frame_processing_does_not_hold_service_monitor() -> None:
    kotlin_source = source()

    assert not re.search(
        r"@Synchronized\s+private\s+fun\s+processImage\s*\(",
        kotlin_source,
    )


def test_frame_generation_is_checked_before_and_after_inference() -> None:
    kotlin_source = source()
    body = function_body(kotlin_source, "processImage")
    current_check = "isFrameCurrent(generation, frameContextGeneration)"
    conversion_end = body.find("frame = frameConverter.convert")
    inference_start = body.find("val hands = tracker.detect")
    pre_inference_check = body.rfind(current_check, 0, inference_start)
    inference_end = body.find("val detectEndNs")
    state_commit = body.find("processHands(")
    post_inference_check = body.find(current_check, inference_end)
    current_body = function_body(kotlin_source, "isFrameCurrent")

    assert body.count(current_check) >= 3
    assert conversion_end < pre_inference_check < inference_start
    assert inference_end >= 0
    assert inference_end < post_inference_check < state_commit
    assert "analysisGeneration.get() == generation" in current_body
    assert "processingContextGeneration.get() == frameContextGeneration" in current_body


def test_pending_camera_start_is_invalidated_by_stop() -> None:
    kotlin_source = source()
    start_body = function_body(kotlin_source, "startCamera")
    stop_body = function_body(kotlin_source, "stopCamera")

    assert "cameraRequestGeneration.incrementAndGet()" in start_body
    assert "cameraRequestGeneration.incrementAndGet()" in stop_body
    assert "isCameraRequestCurrent" in start_body
    assert "cameraPreviewView = null" in stop_body


def test_camera_and_mapping_changes_invalidate_inflight_frames() -> None:
    kotlin_source = source()
    toggle_body = function_body(kotlin_source, "toggleCamera")
    target_body = function_body(kotlin_source, "setTargetHand")
    calibration_body = function_body(kotlin_source, "startCalibration")

    assert toggle_body.find("analysisGeneration.incrementAndGet()") < toggle_body.find(
        "useFrontCamera = !useFrontCamera"
    )
    assert "processingContextGeneration.incrementAndGet()" in toggle_body
    assert "processingContextGeneration.incrementAndGet()" in target_body
    assert "processingContextGeneration.incrementAndGet()" in calibration_body


def test_calibration_immediately_disables_control_until_completed() -> None:
    kotlin_source = source()
    calibration_body = function_body(kotlin_source, "startCalibration")
    process_hands_body = function_body(kotlin_source, "processHands")

    assert "_controlFrame.value = GestureControlFrame(" in calibration_body
    assert "false" in calibration_body
    assert "calibrationState == CalibrationState.CALIBRATED" in process_hands_body


def test_detection_reset_cannot_revive_previous_control_frame() -> None:
    reset_body = function_body(source(), "resetDetectionLoop")

    assert "lastHandDetectedMs = 0L" in reset_body


def test_each_calibration_pose_starts_with_fresh_samples() -> None:
    kotlin_source = source()
    arm_body = function_body(kotlin_source, "recordCalibrationPose")
    complete_body = function_body(kotlin_source, "completeCalibrationPose")
    reset_body = function_body(kotlin_source, "resetDetectionLoop")
    capture_body = function_body(kotlin_source, "updateCalibrationCapture")
    hands_body = function_body(kotlin_source, "processHands")

    # A tap invalidates in-flight inference and clears the collector before arming;
    # it cannot confirm a median taken from pre-tap rolling sample history.
    invalidate = arm_body.find("processingContextGeneration.incrementAndGet()")
    reset = arm_body.find("resetDetectionLoop(clearSmoothing = true)")
    arm = arm_body.find("calibrationCapture.arm(")
    assert 0 <= invalidate < reset < arm
    assert "calibrationCapture.isCollecting" in arm_body
    assert "completeCalibrationPose(" not in arm_body
    assert "medianCalibrationSample" not in kotlin_source
    assert "calibrationCapture.cancel()" in reset_body

    # Both pose transitions still invalidate queued analyzer frames and clear capture.
    assert complete_body.count("processingContextGeneration.incrementAndGet()") >= 2
    assert complete_body.count("resetDetectionLoop(clearSmoothing = true)") >= 2
    assert "CalibrationCaptureResult.Complete -> completeCalibrationPose(result.angles)" in capture_body
    assert "rawAngles = rawAngles" in hands_body
    assert hands_body.find("publishUiState(") < hands_body.find("updateCalibrationCapture(")


def test_capture_loss_and_context_changes_cannot_auto_resume() -> None:
    kotlin_source = source()
    no_hand_body = function_body(kotlin_source, "markNoHand")
    reset_body = function_body(kotlin_source, "resetDetectionLoop")

    assert "calibrationCapture.cancel()" in no_hand_body
    assert "calibrationCapturePhase = CalibrationCapturePhase.IDLE" in no_hand_body
    assert "GestureControlFrame(false" in no_hand_body
    assert "calibrationCapture.cancel()" in reset_body
    assert "calibrationCapture.arm(" not in no_hand_body
    assert "calibrationCapture.arm(" not in reset_body


def test_release_closes_tracker_on_analyzer_executor() -> None:
    release_body = function_body(source(), "release")

    assert "scheduleTrackerClose" in release_body
    assert "cameraExecutor.shutdown()" not in release_body
