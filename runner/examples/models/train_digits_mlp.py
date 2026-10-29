"""Reproducible training of the offload-lab digits MLP (offline; not run in the image).

Data: scikit-learn bundled copy of the UCI "Optical Recognition of Handwritten Digits" test set (8x8, 1797 samples, CC BY 4.0).
Usage: python train_digits_mlp.py OUTPUT.json   (scikit-learn 1.5.2, numpy)
"""
import hashlib
import json
import sys

import numpy as np
import sklearn
from sklearn.datasets import load_digits
from sklearn.model_selection import train_test_split
from sklearn.neural_network import MLPClassifier

digits = load_digits()
x_train, x_test, y_train, y_test = train_test_split(
    digits.data, digits.target, test_size=0.2, random_state=0, stratify=digits.target)
model = MLPClassifier(hidden_layer_sizes=(32,), activation="relu", max_iter=600, random_state=0)
model.fit(x_train / 16.0, y_train)
round6 = lambda a: [round(float(v), 6) for v in np.asarray(a).ravel()]
w1, w2 = model.coefs_
b1, b2 = model.intercepts_


artifact = {
    "format": "edgeai.offload-lab.mlp/v1",
    "name": "digits-mlp-64-32-10",
    "task": "8x8 handwritten digit classification (0-9)",
    "dataset": "UCI Optical Recognition of Handwritten Digits (scikit-learn load_digits), CC BY 4.0",
    "training": {"sklearn": sklearn.__version__, "split": "stratified 80/20, random_state=0", "hiddenLayer": 32,
                 "activation": "relu", "inputScale": "pixel/16", "trainSamples": int(len(y_train))},
    "layers": [
        {"inputs": 64, "outputs": 32, "weights": round6(w1), "bias": round6(b1), "activation": "relu"},
        {"inputs": 32, "outputs": 10, "weights": round6(w2), "bias": round6(b2), "activation": "argmax"},
    ],
    "heldOut": {"pixels": [[int(v) for v in row] for row in x_test], "labels": [int(v) for v in y_test]},
}
artifact["heldOut"]["sklearnAccuracy"] = round(float(model.score(x_test / 16.0, y_test)), 6)
body = json.dumps(artifact, separators=(",", ":"), sort_keys=True).encode()
with open(sys.argv[1], "wb") as out:
    out.write(body)
print(hashlib.sha256(body).hexdigest(), artifact["heldOut"]["sklearnAccuracy"], len(body))
