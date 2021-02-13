package org.labkey.singlecell.analysis;

import org.apache.commons.lang3.StringUtils;
import org.labkey.api.data.Container;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.util.FileType;
import org.labkey.api.view.ActionURL;

import java.util.List;

public class ProcessSeuratObjectHandler extends AbstractSingleCellHandler implements SequenceOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>, SequenceOutputHandler.HasActionNames
{
    private static FileType SEURAT_TYPE = new FileType("seurat.rds", false);

    @Override
    public String getName()
    {
        return "Seurat Processing";
    }

    @Override
    public boolean canProcess(SequenceOutputFile f)
    {
        return f.getFile() != null && SEURAT_TYPE.isType(f.getFile());
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor(false);
    }
}
