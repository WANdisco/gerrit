/**
 * @license
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import '../test/common-test-setup-karma.js';
import {asyncForeach} from './async-util.js';

suite('async-util tests', () => {
  test('loops over each item', () => {
    const fn = sinon.stub().returns(Promise.resolve());
    return asyncForeach([1, 2, 3], fn)
        .then(() => {
          assert.isTrue(fn.calledThrice);
          assert.equal(fn.getCall(0).args[0], 1);
          assert.equal(fn.getCall(1).args[0], 2);
          assert.equal(fn.getCall(2).args[0], 3);
        });
  });

  test('halts on stop condition', () => {
    const stub = sinon.stub();
    const fn = (e, stop) => {
      stub(e);
      stop();
      return Promise.resolve();
    };
    return asyncForeach([1, 2, 3], fn)
        .then(() => {
          assert.isTrue(stub.calledOnce);
          assert.equal(stub.lastCall.args[0], 1);
        });
  });
});
