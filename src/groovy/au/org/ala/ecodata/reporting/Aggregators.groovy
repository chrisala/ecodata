package au.org.ala.ecodata.reporting

import org.apache.log4j.Logger

import java.text.NumberFormat
import java.text.ParseException

/**
 * Convenience class to group together implementations of various types of aggregration functions (summing, counting etc)
 */
class Aggregators {

    def log = Logger.getLogger(getClass())

    public static abstract class OutputAggregator {

        String group
        Score score
        int count;
        boolean scoreNested = false

        public void setScore(Score score) {
            this.score = score
            if (score.name.contains('.')) {
                scoreNested = true
            }

        }

        public void aggregate(output) {
            if (output.name == score.outputName) {
                if (score.listName) {
                    output.data[score.listName].each{aggregateValue(it[score.name])}
                }
                else {
                    def val = getValue(output)
                    if (val instanceof List) {
                        val.each {aggregateValue(it)}
                    }
                    else {
                        aggregateValue(val)
                    }
                }
            }
        }

        public void aggregateValue(value) {
            if (value != null) {
                count++;
                doAggregation(value);
            }
        }

        def getValue(output) {
            if (scoreNested) {
                return Eval.x(output.data, 'x.'+score.name.replace('.', '?.'))
            }
            return output.data[score.name]
        }

        def getValueAsNumeric(value) {
            def numeric = null
            if (value instanceof String) {
                NumberFormat format = NumberFormat.getInstance(Locale.default)

                try {
                    numeric = format.parse(value)
                }
                catch (ParseException e) {
                    log.warn("Attemping to aggregate non-numeric value: ${value} for score: ${score.outputName}:${score.label}")
                }
            }
            else if (value instanceof Number) {
                numeric = value
            }
            else {
                log.warn("Attemping to aggregate non-numeric value: ${value} for score: ${score.outputName}:${score.label}")
            }
            return numeric
        }

        public abstract void doAggregation(output);

        public abstract AggregrationResult result();



    }

    /**
     * Returns an average of the aggregated output scores
     */
    static class AverageAggregator extends OutputAggregator {

        double total

        public void doAggregation(value) {
            def numericValue = getValueAsNumeric(value)
            if (numericValue) {
                total += numericValue
            }
        }

        public AggregrationResult result() {
            return new AggregrationResult([score:score, group:group, count: count, result:count > 0 ? total/count : 0])
        }
    }

    /**
     * Returns a the sum of the aggregated output scores
     */
    static class SummingAggegrator extends OutputAggregator {

        double total

        public void doAggregation(value) {

            def numericValue = getValueAsNumeric(value)
            if (numericValue) {
                total += numericValue
            }

        }
        public AggregrationResult result() {
            return new AggregrationResult([score:score, group:group, count:count, result:total])
        }

    }

    /**
     * Returns the count of outputs containing the score. TODO counting entities may make more sense.
     */
    static class CountingAggregator extends OutputAggregator {

        public void doAggregation(output) {}

        public AggregrationResult result() {
            // Units don't make sense for a count, regardless of the units of the score.
            return new AggregrationResult([score:score, units:"", group:group, count:count, result:count])
        }
    }

    /**
     * Returns a Map with keys being distinct values of the score and values being the number of times
     * that score value occurred.
     */
    static class HistogramAggregator extends OutputAggregator {

        Map histogram = [:].withDefault { 0 };

        public void doAggregation(value) {

            if (value =~ /name:/) {
                // extract sci name from complex key. e.g.
                // [guid:urn:lsid:biodiversity.org.au:apni.taxon:56760, listId:Atlas of Living Australia, name:Paspalum punctatum, list:]
                // TODO move this code to somewhere else where the string has not already been encoded
                //log.debug "value = '${value}'"
                def m = value =~ /(?:name:)(.*?),/
                //log.debug "m = ${m[0][1]?:'[unknown]'} "
                value = m[0][1]?:'[unknown]'
            }
            histogram[value]++
        }

        public AggregrationResult result() {
            // Units don't make sense for a count, regardless of the units of the score.
            return new AggregrationResult([score:score, units:"", group:group, count:count, result:histogram])
        }
    }

    /**
     * Returns Set containing distinct values of the output score
     */
    static class SetAggregator extends OutputAggregator {

        List values = []

        public void doAggregation(value) {
            values << value
        }

        public AggregrationResult result() {
            // Units don't make sense for a count, regardless of the units of the score.
            return new AggregrationResult([score:score, units:"", group:group, count:count, result:values])
        }
    }


    /**
     * Defines the format of the result of the aggregation
     */
    static class AggregrationResult {
        /** The score label */
        String score

        String outputName

        /** The units of the aggregation */
        String units
        /** The group that was aggregrated */
        String group

        int count;

        /**
         * The result of the aggregation. Normally will be a numerical value (e.g. for a sum etc) or a List (for a collecting aggregator)
         */
        def result

        public String toString() {
            return "$outputName:$score:,count=$count,result=$result"
        }
    }




}
