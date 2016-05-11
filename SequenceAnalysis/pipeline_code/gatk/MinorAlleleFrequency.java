
package org.broadinstitute.gatk.tools.walkers.annotator;


import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextUtils;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.broadinstitute.gatk.tools.walkers.annotator.interfaces.AnnotatorCompatible;
import org.broadinstitute.gatk.utils.contexts.AlignmentContext;
import org.broadinstitute.gatk.utils.contexts.ReferenceContext;
import org.broadinstitute.gatk.utils.genotyper.PerReadAlleleLikelihoodMap;
import org.broadinstitute.gatk.utils.refdata.RefMetaDataTracker;

import java.util.*;

/**
 * Calculates MAF based on AF field
 *
 *
 *
 *
 */

public class MinorAlleleFrequency extends ChromosomeCounts {

    private Set<String> founderIds = new HashSet<String>();
    private boolean didUniquifiedSampleNameCheck = false;

    public static final String MAF_KEY = "MAF";

    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> stratifiedPerReadAlleleLikelihoodMap) {

        if ( ! vc.hasGenotypes() )
            return null;
        //if none of the "founders" are in the vc samples, assume we uniquified the samples upstream and they are all founders
        if (!didUniquifiedSampleNameCheck) {
            checkSampleNames(vc);
            didUniquifiedSampleNameCheck = true;
        }

        Map<String, Object> chrCounts = VariantContextUtils.calculateChromosomeCounts(vc, new HashMap<String, Object>(), true, founderIds);
        if (!chrCounts.containsKey(VCFConstants.ALLELE_FREQUENCY_KEY))
        {
            return null;
        }

        List<Double> afVals;
        if (chrCounts.get(VCFConstants.ALLELE_FREQUENCY_KEY) instanceof List)
        {
            afVals = new ArrayList<Double>((List)chrCounts.get(VCFConstants.ALLELE_FREQUENCY_KEY));
        }
        else if (chrCounts.get(VCFConstants.ALLELE_FREQUENCY_KEY) instanceof Double)
        {
            afVals = new ArrayList<>();
            afVals.add((Double)chrCounts.get(VCFConstants.ALLELE_FREQUENCY_KEY));
        }
        else
        {
            return null;
        }

        Map<String, Object> attributeMap = new HashMap<>();
        if (afVals.size() > 1)
        {
            afVals.remove(0);  //ignore the reference

            Double maf = Collections.max(afVals);
            attributeMap.put(MAF_KEY, maf);
        }
        else if (afVals.size() == 1)
        {
            attributeMap.put(MAF_KEY, 0.0);
        }

        return attributeMap;
    }

    // return the descriptions used for the VCF INFO meta field
    public List<String> getKeyNames() { return Arrays.asList(MAF_KEY); }

    public List<VCFInfoHeaderLine> getDescriptions() { return Arrays.asList(
            new VCFInfoHeaderLine(MAF_KEY, 1, VCFHeaderLineType.Float, "The minor allele frequency, derived from the AF field."));
    }
}
