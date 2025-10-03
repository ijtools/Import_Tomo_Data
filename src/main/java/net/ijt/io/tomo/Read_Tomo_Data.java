package net.ijt.io.tomo;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;

/**
 * Opens a file chooser to select a file with ".vol" extension, then parse the
 * content of the associated ".vol.info" file, and read the content of the data
 * file.
 * 
 * @author dlegland
 *
 */
public class Read_Tomo_Data implements PlugIn
{
    private static String lastOpenDir = OpenDialog.getDefaultDirectory();

    private static final FileFilter VOL_FILE_FILTER = new FileNameExtensionFilter("Tomo Data volume (*.vol)", "vol");

    public static final File chooseFileToOpen(String title, FileFilter... fileFilters)
    {
        if (!Prefs.useJFileChooser)
        { return chooseFileToOpen_awt(title, fileFilters); }

        // create dialog using last open path
        final JFileChooser fileChooser = new JFileChooser(lastOpenDir);

        // setup dialog title
        if (title != null)
        {
            fileChooser.setDialogTitle(title);
        }

        // add optional file filters
        for (FileFilter filter : fileFilters)
        {
            fileChooser.addChoosableFileFilter(filter);
        }
        if (fileFilters.length > 0)
        {
            fileChooser.setFileFilter(fileFilters[0]);
        }

        // Open dialog to choose the file
        Frame parent = IJ.getInstance();
        int ret = fileChooser.showOpenDialog(parent);
        if (ret != JFileChooser.APPROVE_OPTION)
        { return null; }

        File selectedFile = fileChooser.getSelectedFile();
        lastOpenDir = selectedFile.toPath().getParent().toString();

        // return the selected file
        return selectedFile;
    }

    private static final File chooseFileToOpen_awt(String title, FileFilter... fileFilters)
    {
        Frame parent = IJ.getInstance();

        FileDialog dlg = new FileDialog(parent, "Choose a file", FileDialog.LOAD);
        dlg.setDirectory(lastOpenDir);

        // Try to add file filters, but does not seem to work with Windows...
        if (fileFilters.length > 0)
        {
            IJ.log("add file filter");
            final FileFilter filter = fileFilters[0];
            dlg.setFilenameFilter(new FilenameFilter()
            {
                @Override
                public boolean accept(File dir, String name)
                {
                    return filter.accept(new File(dir, name));
                }
            });
        }

        dlg.setVisible(true);

        String file = dlg.getFile();
        if (file == null) return null;
        String dir = dlg.getDirectory();

        // store directory for next call to plugin
        lastOpenDir = dir;

        return new File(dir, file);
    }

    @Override
    public void run(String arg)
    {
        // get the file
        String pathToFile = arg;

        Path filePath = null;
        if (null == pathToFile || 0 == pathToFile.length())
        {
            File volFile = chooseFileToOpen("Choose .vol file", VOL_FILE_FILTER);
            if (volFile == null) return;
            filePath = volFile.toPath();
        }
        else
        {
            // convert input argument into Path object
            filePath = Paths.get(pathToFile);
        }

        String fileName = filePath.getFileName().toString(); 
        if (!fileName.endsWith(".vol"))
        {
            System.err.println("Expect input file extension to be '.vol', abort");
            return;
        }

        // check existence of vol.info file
        Path pathToInfoFile = filePath.resolveSibling(fileName + ".info");
        if (!Files.exists(pathToInfoFile))
        {
            System.err.println("Could not find vol.info file, abort");
            return;
        }

        
        // read image data
        ImagePlus imagePlus;
        try
        {
            ImageInfo info = readImageInfo(pathToInfoFile);
            
            InputStream inputStream = new BufferedInputStream(new FileInputStream(filePath.toFile()));
            
            ImageStack stack = readImageData(inputStream, info);
            
            imagePlus = new ImagePlus(fileName, stack);
            Calibration calib = new Calibration();
            calib.pixelWidth = info.pixelSize;
            calib.pixelHeight = info.pixelSize;
            calib.pixelDepth = info.pixelSize;
            imagePlus.setCalibration(calib);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            IJ.error("Image import error", e.getMessage());
            return;
        }

        imagePlus.show();

        ImageStack stack = imagePlus.getStack();
        int currentSliceIndex = (int) Math.floor(stack.getSize() / 2.0) + 1;
        imagePlus.setSlice(currentSliceIndex);
        stack.getProcessor(currentSliceIndex).resetMinAndMax();
    }

    private ImageInfo readImageInfo(Path pathToInfoFile) throws IOException
    {
        try(LineNumberReader reader = new LineNumberReader(new FileReader(pathToInfoFile.toFile())))
        {
            ImageInfo info = new ImageInfo();
            // header line
            reader.readLine();

            // tokens = valueString.split(" ");
            // size of the stack
            String xSizeString = reader.readLine().split("=")[1].trim();
            info.sizeX = Integer.parseInt(xSizeString);
            String ySizeString = reader.readLine().split("=")[1].trim();
            info.sizeY = Integer.parseInt(ySizeString);
            String zSizeString = reader.readLine().split("=")[1].trim();
            info.sizeZ = Integer.parseInt(zSizeString);

            // resolution
            String resolString = reader.readLine();
            info.pixelSize = Double.parseDouble(resolString.split("=")[1].trim());
            
            return info;
        }
    }

    private ImageStack readImageData(InputStream inputStream, ImageInfo info) throws IOException
    {
        // allocate stack
        IJ.showStatus("Create image stack");
        ImageStack stack = ImageStack.create(info.sizeX, info.sizeY, info.sizeZ, 32);

        IJ.showStatus("Read raw data");

        // allocate byte array for current slice
        int pixelsPerPlane = info.sizeX * info.sizeY;
        int bytesPerPlane = pixelsPerPlane * 4;
        byte[] byteData = new byte[bytesPerPlane];

        // iterate over slices
        for (int z = 0; z < info.sizeZ; z++)
        {
            IJ.showProgress(z, info.sizeZ);
            // read current slice
            int nRead = inputStream.read(byteData, 0, bytesPerPlane);

            // check whole slice was correctly read
            if (nRead != bytesPerPlane)
            {
                inputStream.close();
                throw new RuntimeException("Could read only " + nRead + " over the " + bytesPerPlane + " expected");
            }

            // convert byte array to ShortProcessor
            float[] data = convertToFloatArray(byteData, info.littleEndian);
            FloatProcessor slice = new FloatProcessor(info.sizeX, info.sizeY, data, null);

            stack.setProcessor(slice, z + 1);
        }

        inputStream.close();

        IJ.showProgress(1, 1);
        IJ.showStatus("");

        return stack;
    }
    
    private float[] convertToFloatArray(byte[] byteData, boolean littleEndian)
    {
        // allocate short array
        int size = byteData.length / 4;
        float[] data = new float[size];

        // convert byte pairs to shorts
        if (littleEndian)
        {
            for (int i = 0; i < size; i++)
            {
                byte b1 = byteData[4 * i];
                byte b2 = byteData[4 * i + 1];
                byte b3 = byteData[4 * i + 2];
                byte b4 = byteData[4 * i + 3];

                int v = ((b4 & 0xFF) << 24 | (b3 & 0xFF) << 16 | (b2 & 0xFF) << 8 | (b1 & 0x00FF));
                data[i] = Float.intBitsToFloat(v);
            }
        }
        else
        {
            for (int i = 0; i < size; i++)
            {
                byte b1 = byteData[2 * i];
                byte b2 = byteData[2 * i + 1];
                data[i] = (short) ((b1 & 0xFF) << 8 | (b2 & 0xFF));
            }
        }
        return data;
    }
    
    class ImageInfo 
    {
        int sizeX;
        int sizeY;
        int sizeZ;
        boolean littleEndian = true;
        double pixelSize;
    }
}
