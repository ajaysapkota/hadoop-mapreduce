/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapreduce.v2.app.speculate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.v2.YarnMRJobConfig;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent.TaskAttemptStatus;
import org.apache.hadoop.mapreduce.v2.api.TaskAttemptID;
import org.apache.hadoop.mapreduce.v2.api.TaskID;

/*
 * This estimator exponentially smooths the rate of progress vrs. wallclock
 *  time.  Conceivably we could write an estimator that smooths time per
 *  unit progress, and get different results.
 */
public class ExponentiallySmoothedTaskRuntimeEstimator extends StartEndTimesBase {

  private final ConcurrentMap<TaskAttemptID, AtomicReference<EstimateVector>> estimates
      = new ConcurrentHashMap<TaskAttemptID, AtomicReference<EstimateVector>>();

  private SmoothedValue smoothedValue;

  private long lambda;

  public enum SmoothedValue {
    RATE, TIME_PER_UNIT_PROGRESS
  }

  ExponentiallySmoothedTaskRuntimeEstimator
      (long lambda, SmoothedValue smoothedValue) {
    super();
    this.smoothedValue = smoothedValue;
    this.lambda = lambda;
  }

  public ExponentiallySmoothedTaskRuntimeEstimator() {
    super();
  }

  // immutable
  private class EstimateVector {
    final double value;
    final float basedOnProgress;
    final long atTime;

    EstimateVector
        (double value, float basedOnProgress, long atTime) {
      this.value = value;
      this.basedOnProgress = basedOnProgress;
      this.atTime = atTime;
    }

    EstimateVector incorporate(float newProgress, long newAtTime) {
      if (newAtTime <= atTime || newProgress < basedOnProgress) {
        return this;
      }

      double oldWeighting
          = value < 0.0
              ? 0.0 : Math.exp(((double) (newAtTime - atTime)) / lambda);

      double newRead = (newProgress - basedOnProgress) / (newAtTime - atTime);

      if (smoothedValue == SmoothedValue.TIME_PER_UNIT_PROGRESS) {
        newRead = 1.0 / newRead;
      }

      return new EstimateVector
          (value * oldWeighting + newRead * (1.0 - oldWeighting),
           newProgress, newAtTime);
    }
  }

  private void incorporateReading
      (TaskAttemptID attemptID, float newProgress, long newTime) {
    AtomicReference<EstimateVector> vectorRef = estimates.get(attemptID);

    if (vectorRef == null) {
      estimates.putIfAbsent(attemptID, new AtomicReference<EstimateVector>(null));
      incorporateReading(attemptID, newProgress, newTime);
    }

    EstimateVector oldVector = vectorRef.get();

    if (oldVector == null) {
      if (vectorRef.compareAndSet(null,
             new EstimateVector(-1.0, 0.0F, Long.MIN_VALUE))) {
        return;
      }

      incorporateReading(attemptID, newProgress, newTime);
    }

    while (!vectorRef.compareAndSet
            (oldVector, oldVector.incorporate(newProgress, newTime))) {
      oldVector = vectorRef.get();
    }
  }

  private EstimateVector getEstimateVector(TaskAttemptID attemptID) {
    AtomicReference<EstimateVector> vectorRef = estimates.get(attemptID);

    if (vectorRef == null) {
      return null;
    }

    return vectorRef.get();
  }

  private static final long DEFAULT_EXPONENTIAL_SMOOTHING_LAMBDA_MILLISECONDS
      = 1000L * 60;

  @Override
  public void contextualize(Configuration conf, AppContext context) {
    super.contextualize(conf, context);

    lambda
        = conf.getLong(YarnMRJobConfig.EXPONENTIAL_SMOOTHING_LAMBDA_MILLISECONDS,
            DEFAULT_EXPONENTIAL_SMOOTHING_LAMBDA_MILLISECONDS);
    smoothedValue
        = conf.getBoolean(YarnMRJobConfig.EXPONENTIAL_SMOOTHING_SMOOTH_RATE, true)
            ? SmoothedValue.RATE : SmoothedValue.TIME_PER_UNIT_PROGRESS;
  }

  @Override
  public long estimatedRuntime(TaskAttemptID id) {
    Long startTime = startTimes.get(id);

    if (startTime == null) {
      return -1L;
    }

    EstimateVector vector = getEstimateVector(id);

    if (vector == null) {
      return -1L;
    }

    long sunkTime = vector.atTime - startTime;

    double value = vector.value;
    float progress = vector.basedOnProgress;

    if (value == 0) {
      return -1L;
    }

    double rate = smoothedValue == SmoothedValue.RATE ? value : 1.0 / value;

    if (rate == 0.0) {
      return -1L;
    }

    double remainingTime = (1.0 - progress) / rate;

    return sunkTime + (long)remainingTime;
  }

  @Override
  public long runtimeEstimateVariance(TaskAttemptID id) {
    return -1L;
  }

  @Override
  public void updateAttempt(TaskAttemptStatus status, long timestamp) {
    TaskAttemptID attemptID = status.id;

    float progress = status.progress;

    incorporateReading(attemptID, progress, timestamp);
  }
}