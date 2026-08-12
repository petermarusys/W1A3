# ======================================================================
# [노트북 맨 마지막에 추가] STEP 5. TFLite 변환 (1-스텝 모델 방식)
# 이 셀은 "CHAPTER 4. Generating Music" 이후, LSTM_cell / densor / n_a / n_values /
# indices_values 가 이미 메모리에 있는 상태에서 실행하세요.
#
# 기존 방식(inference_model 통째로 변환)과의 차이:
#   - 기존: "50번 반복"을 그래프 안에 파이썬 for문으로 미리 다 박아넣은
#           inference_model을 그대로 변환 -> 그래프가 50배로 펼쳐져 거대해짐
#   - 이번: LSTM 셀이 "딱 한 스텝"만 처리하는 작은 모델을 변환하고,
#           "몇 번 반복할지(Ty)"는 안드로이드 앱의 for문에서 결정
#     -> Netron 그래프가 훨씬 작아지고, 생성 길이를 바꿀 때 재변환이 필요 없음
# ======================================================================

import tensorflow as tf
from tensorflow.keras.layers import Input
from tensorflow.keras.models import Model
import json

# ----------------------------------------------------------------------
# 1) "한 스텝"짜리 모델 정의
#    입력: 현재 노트(x_t, 원-핫), 이전 hidden state(a_prev), 이전 cell state(c_prev)
#    출력: 다음 노트 확률분포(note_probs), 다음 hidden state(a_next), 다음 cell state(c_next)
#
#    주의: 원본 과제의 music_inference_model()은 densor를 "a"(hidden state)에
#    적용합니다(out = densor(a)). 학습 때(djmodel)는 densor(c)를 썼지만, 실제로
#    음악을 생성하는 추론 단계는 densor(a)를 쓰는 게 원본 과제의 정의이므로
#    그대로 따릅니다.
# ----------------------------------------------------------------------
n_values = densor.units   # 90
n_a = LSTM_cell.units     # 64

x_t   = Input(shape=(1, n_values), name="x_t")
a_prev = Input(shape=(n_a,), name="a_prev")
c_prev = Input(shape=(n_a,), name="c_prev")

a_next, _, c_next = LSTM_cell(inputs=x_t, initial_state=[a_prev, c_prev])
note_probs = densor(a_next)

one_step_model = Model(
    inputs=[x_t, a_prev, c_prev],
    outputs=[note_probs, a_next, c_next],
)

one_step_model.summary()

# ----------------------------------------------------------------------
# 2) TFLite 변환
#    한 스텝짜리 LSTM 셀 호출만 남아 있어서 대부분 TFLite 기본 연산셋만으로
#    변환됩니다. 혹시 일부 연산이 기본 연산셋에 없다는 오류가 나면
#    SELECT_TF_OPS 줄의 주석을 해제하세요.
# ----------------------------------------------------------------------
converter = tf.lite.TFLiteConverter.from_keras_model(one_step_model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS,
    # tf.lite.OpsSet.SELECT_TF_OPS,   # 필요 시 주석 해제
]
tflite_model = converter.convert()

with open("jazz_solo_model.tflite", "wb") as f:
    f.write(tflite_model)

print(f"jazz_solo_model.tflite 저장 완료 ({len(tflite_model)/1024:.1f} KB)")

# ----------------------------------------------------------------------
# 3) indices_values 매핑 저장 (기존과 동일)
# ----------------------------------------------------------------------
with open("indices_values.json", "w", encoding="utf-8") as f:
    json.dump({str(k): str(v) for k, v in indices_values.items()}, f, ensure_ascii=False, indent=2)

print("indices_values.json 저장 완료")

# ----------------------------------------------------------------------
# 4) (선택) Colab이라면 바로 다운로드
# ----------------------------------------------------------------------
try:
    from google.colab import files
    files.download("jazz_solo_model.tflite")
    files.download("indices_values.json")
except ImportError:
    pass

print("\n다음 두 파일을 Android 프로젝트의 app/src/main/assets/ 폴더에 복사하세요:")
print(" - jazz_solo_model.tflite  (파일명이 바뀌었으니 MusicGenerator.kt의 모델 파일명도 맞춰주세요)")
print(" - indices_values.json")
