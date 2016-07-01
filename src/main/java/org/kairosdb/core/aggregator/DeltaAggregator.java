/*
 * Copyright 2013 Proofpoint Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.kairosdb.core.aggregator;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.kairosdb.core.DataPoint;
import org.kairosdb.core.aggregator.annotation.AggregatorName;
import org.kairosdb.core.datapoints.DoubleDataPointFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Converts all longs to double. This will cause a loss of precision for very large long values.
 */
@AggregatorName(name = "delta", description = "distance of previous data points")
public class DeltaAggregator extends RangeAggregator
{
	private DoubleDataPointFactory m_dataPointFactory;

	@Inject
	public DeltaAggregator(DoubleDataPointFactory dataPointFactory)
	{
		m_dataPointFactory = dataPointFactory;
	}

	@Override
	public boolean canAggregate(String groupType)
	{
		return DataPoint.GROUP_NUMBER.equals(groupType);
	}

	@Override
	protected RangeSubAggregator getSubAggregator()
	{
		return (new DeltaDataPointAggregator());
	}

	private class DeltaDataPointAggregator implements RangeSubAggregator {

        final double THRESHOLD = 0.0001;

        private boolean equals(double v1, double v2) {
            return Math.abs(v2 - v1) < THRESHOLD;
        }

        private boolean notMoreThan(double v1, double v2) {
            return v1 < v2 || equals(v1, v2);
        }

        @Override
        public Iterable<DataPoint> getNextDataPoints(long returnTime, Iterator<DataPoint> dataPointRange) {
            return getNextDataPoints2(returnTime, dataPointRange);
        }


		public Iterable<DataPoint> getNextDataPoints1(long returnTime, Iterator<DataPoint> dataPointRange) {
			List<DataPoint> originDataPoints = Lists.newArrayList();
			while (dataPointRange.hasNext()) {
				originDataPoints.add(dataPointRange.next());
			}

            double lastValue = 0.0;
            for (int i = originDataPoints.size()-1; i >= 0; i--) {
                if (!equals((lastValue = originDataPoints.get(i).getDoubleValue()), 0.0))
                    break;
            }

            // get the incremental sequence, because it is possible some data point with value 0
			List<DataPoint> incDataPoints = Lists.newArrayList();
            int preIndex = 0;
            long totalGap = 0L;
            int countGap = 0;
            boolean isFirst = true;
			for (int i = 0; i < originDataPoints.size(); i++) {
                DataPoint curDataPoint = originDataPoints.get(i);
                if (notMoreThan(curDataPoint.getDoubleValue(), lastValue)) {
                    if (isFirst) {
                        incDataPoints.add(curDataPoint);
                        preIndex = i;
                        isFirst = false;
                    } else {
                        DataPoint preDataPoint = originDataPoints.get(preIndex);
                        if (notMoreThan(preDataPoint.getDoubleValue(), curDataPoint.getDoubleValue())) {
                            incDataPoints.add(curDataPoint);
                            if (preIndex == i - 1) {
                                totalGap += curDataPoint.getTimestamp() - preDataPoint.getTimestamp();
                                countGap++;
                            }
                            preIndex = i;
                        }
                    }
                }
			}

            // calc the avg gap between inc sequence data points
            Long AVG_GAP = countGap > 0 ? (totalGap / countGap) : (Long.MAX_VALUE / 5);

            // calc the diff
			List<DataPoint> results = Lists.newArrayList();
			for (int i = 0; i < incDataPoints.size(); i++) {
                DataPoint cur = incDataPoints.get(i);
                double diff;
                if (i == 0) {
                    diff = 0;   // diff for the first element set to 0
                }
                else {
                    DataPoint pre = incDataPoints.get(i-1);
                    if (cur.getTimestamp() - pre.getTimestamp() > (AVG_GAP * 5)) {
                        diff = 0;   // if the gap is large than 5 times of avg gap, take it as start of a new seg
                    }
                    else {
                        diff = cur.getDoubleValue() - pre.getDoubleValue();
                    }
                }

                if (diff > 0) {
                    results.add(m_dataPointFactory.createDataPoint(cur.getTimestamp(), diff));
                }
			}
			return results;
		}

        public Iterable<DataPoint> getNextDataPoints2(long returnTime, Iterator<DataPoint> dataPointRange) {
            // simple rules to trim some outliers:
            // 1. diff of start point of a segment (some consecutive points) is 0
            // 2. if the gap (the diff of timestamp) for a point is 4 times of previous avg gap,
            //    take this point as a new start of a segment
            // 3. trim the diff which is negative
            // 4. trim the diff which is large than the 1/16 of the current value (for value > 10000)
            if (!dataPointRange.hasNext()) {
                return Collections.emptyList();
            }

            List<DataPoint> results = new ArrayList<>();

            DataPoint pre = dataPointRange.next();
            results.add(m_dataPointFactory.createDataPoint(pre.getTimestamp(), 0));

            int count = 0;  // count for gaps
            long gaps = 0L; // total of gaps
            while (dataPointRange.hasNext()) {
                DataPoint cur = dataPointRange.next();
                double value = cur.getDoubleValue();
                double diff = value - pre.getDoubleValue();
                if (notMoreThan(0.0, diff) && (notMoreThan(value, 10000) || notMoreThan(diff , value / 16))) {
                    long gap = cur.getTimestamp() - pre.getTimestamp();
                    double delta = 0;
                    if (count == 0 || gap < ((gaps / count) << 2)) { // 4 times of previous avg gap
                        delta = diff;
                        count ++;
                        gaps += gap;
                    }
                    results.add(m_dataPointFactory.createDataPoint(cur.getTimestamp(), delta));
                }
                pre = cur;
            }
            return results;
        }
	}
}