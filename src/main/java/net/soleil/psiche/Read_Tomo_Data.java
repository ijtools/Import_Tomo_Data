package net.soleil.psiche;

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
        String path = arg;
        String directory = null;
        String fileName = null;

        if (null == path || 0 == path.length())
        {
            File volFile = chooseFileToOpen("Choose .vol file", VOL_FILE_FILTER);
            if (volFile == null) return;
            directory = volFile.getParent();
            fileName = volFile.getName();
            path = directory + "/" + fileName;
        }
        else
        {
            // the argument is the path
            File fileIn = new File(path);
            directory = fileIn.getParent(); // could be a URL
            fileName = fileIn.getName();
        }

        if (!fileName.toLowerCase().endsWith(".vol"))
        {
            System.err.println("Expect input file extension to be '.vol', abort");
            return;
        }

        // check existence of vol.info file
        String infoFileName = fileName + ".info";
        File file = new File(directory, infoFileName);
        if (!file.exists())
        {
            System.err.println("Could not find vol.info file, abort");
            return;
        }

        // read image data
        ImagePlus imagePlus;
        try
        {
            imagePlus = readImagePlus(file);
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

    public ImagePlus readImagePlus(File file) throws IOException
    {
        // String dataFileName = null;
        int sizeX = 0;
        int sizeY = 0;
        int sizeZ = 0;
        // int bitDepth = 32;
        boolean littleEndian = true;

        Calibration calib = null;

        LineNumberReader reader = new LineNumberReader(new FileReader(file));

        // header line
        reader.readLine();

        // tokens = valueString.split(" ");
        // size of the stack
        String xSizeString = reader.readLine().split("=")[1].trim();
        sizeX = Integer.parseInt(xSizeString);
        String ySizeString = reader.readLine().split("=")[1].trim();
        sizeY = Integer.parseInt(ySizeString);
        String zSizeString = reader.readLine().split("=")[1].trim();
        sizeZ = Integer.parseInt(zSizeString);

        // resolution
        String resolString = reader.readLine();
        double resol = Double.parseDouble(resolString.split("=")[1].trim());

        reader.close();

        // determine data file name
        String fileName = file.getName();
        String dataFileName = file.getName().substring(0, fileName.length() - 5);
        System.out.println("read data file: " + dataFileName);
        File dataFile = new File(file.getParentFile(), dataFileName);
        IJ.log("read data file: " + dataFile.getAbsolutePath());
        IJ.log("  Volume size: " + sizeX + "x" + sizeY + "x" + sizeZ);

        // read image data
        // (assumes all necessary information have been read)
        ImageStack stack = readData(dataFile, sizeX, sizeY, sizeZ, 32, littleEndian);

        ImagePlus imagePlus = new ImagePlus(file.getName(), stack);
        calib = new Calibration();
        calib.pixelWidth = resol;
        calib.pixelHeight = resol;
        calib.pixelDepth = resol;
        imagePlus.setCalibration(calib);

        return imagePlus;
    }

    private ImageStack readData(File file, int sizeX, int sizeY, int sizeZ, int bitDepth, boolean littleEndian) throws IOException
    {
        // First validates the existence of the file
        if (!file.exists())
        { throw new RuntimeException("Could not find data file: " + file.getName()); }

        // allocate stack
        IJ.showStatus("Create image stack");
        ImageStack stack = ImageStack.create(sizeX, sizeY, sizeZ, bitDepth);

        IJ.showStatus("Read raw data");

        // allocate byte array for current slice
        int pixelsPerPlane = sizeX * sizeY;
        int nBytes = pixelsPerPlane * 4;
        byte[] byteData = new byte[nBytes];

        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
        // iterate over slices
        for (int z = 0; z < sizeZ; z++)
        {
            IJ.showProgress(z, sizeZ);
            // read current slice
            int nRead = inputStream.read(byteData, 0, nBytes);

            // check whole slice was correctly read
            if (nRead != nBytes)
            {
                inputStream.close();
                throw new RuntimeException("Could read only " + nRead + " over the " + nBytes + " expected");
            }

            // convert byte array to ShortProcessor
            float[] data = convertToFloatArray(byteData, littleEndian);
            FloatProcessor slice = new FloatProcessor(sizeX, sizeY, data, null);

            stack.setProcessor(slice, z + 1);
        }

        inputStream.close();

        IJ.showProgress(1, 1);
        IJ.showStatus("");

        return stack;
    }

    // private short[] convertToShortArray(byte[] byteData, boolean
    // littleEndian)
    // {
    // // allocate short array
    // int size = byteData.length / 2;
    // short[] data = new short[size];
    //
    // // convert byte pairs to shorts
    // if (littleEndian)
    // {
    // for (int i = 0; i < size; i++)
    // {
    // byte b1 = byteData[2 * i];
    // byte b2 = byteData[2 * i + 1];
    //
    // int v = ((b2 & 0xFF) << 8 | (b1 & 0x00FF));
    // data[i] = (short) v;
    // }
    // }
    // else
    // {
    // for (int i = 0; i < size; i++)
    // {
    // byte b1 = byteData[2 * i];
    // byte b2 = byteData[2 * i + 1];
    // data[i] = (short) ((b1 & 0xFF) << 8 | (b2 & 0xFF));
    // }
    // }
    // return data;
    // }

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

    /**
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException
    {
        System.out.println("Test Read_VGI file");

        String fileName = "Grain_daa11.vgi";
        File file = new File("D:/images/wheat/perigrain/clermont/demo-tomo/grain_daa11/" + fileName);
        if (!file.exists())
        {
            System.err.println("Could not file file: " + fileName);
            return;
        }

        Read_Tomo_Data reader = new Read_Tomo_Data();
        ImagePlus imagePlus = reader.readImagePlus(file);

        ImageStack stack = imagePlus.getStack();
        double value0 = stack.getVoxel(0, 0, 0);
        System.out.println("value at (0,0,0): " + value0);
        double value1 = stack.getVoxel(55, 0, 0);
        System.out.println("value at (55,0,0): " + value1);
        double value2 = stack.getVoxel(200, 100, 300);
        System.out.println("value at (200,100,300): " + value2);
        double value3 = stack.getVoxel(0, 0, 5);
        System.out.println("value at (0,0,5): " + value3);
        double value4 = stack.getVoxel(0, 0, 10);
        System.out.println("value at (0,0,10): " + value4);
        value1 = stack.getVoxel(55, 0, 0);
        System.out.println("value at (55,0,0): " + value1);
    }

}
