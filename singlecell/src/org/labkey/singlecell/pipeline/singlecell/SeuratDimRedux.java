package org.labkey.singlecell.pipeline.singlecell;

import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.singlecell.pipeline.SingleCellStep;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class SeuratDimRedux extends AbstractOosapStep
{
    public SeuratDimRedux(PipelineContext ctx, SeuratDimRedux.Provider provider)
    {
        super(provider, ctx);
    }

    public static class Provider extends AbstractPipelineStepProvider<SingleCellStep>
    {
        public Provider()
        {
            super("SeuratDimRedux", "Seurat DimRedux", "OOSAP", "This will use OOSAP to run Seurat's standard DimRedux steps.", Arrays.asList(

            ), null, null);
        }


        @Override
        public SeuratDimRedux create(PipelineContext ctx)
        {
            return new SeuratDimRedux(ctx, this);
        }
    }

    @Override
    public Output execute(List<File> inputObjects, SeuratContext ctx)
    {
        return null;
    }
}
