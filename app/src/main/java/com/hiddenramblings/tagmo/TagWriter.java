package com.hiddenramblings.tagmo;

import com.hiddenramblings.tagmo.ptag.PTagKeyManager;

import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.NfcA;

import java.io.IOException;

public class TagWriter {
  private final MifareUltralight m_mifare;
    private final NfcA m_nfcA;
      private static final int NXP_MANUFACTURER_ID = 0x04;

        private static final String TAG = TagWriter.class.getSimpleName();
    

    private static final byte[] POWERTAG_SIGNATURE = Util.hexStringToByteArray("213C65444901602985E9F6B50CACB9C8CA3C4BCD13142711FF571CF01E66BD6F");
    private static final byte[] POWERTAG_IDPAGES = Util.hexStringToByteArray("04070883091012131800000000000000");
    private static final String POWERTAG_KEY = "FFFFFFFFFFFFFFFF0000000000000000";
    private static final byte[] COMP_WRITE_CMD = Util.hexStringToByteArray("a000");
    private static final byte[] SIG_CMD = Util.hexStringToByteArray("3c00");
        public static final int CMD_WRITE = 0xA2;

public TagWriter(MifareUltralight mifare) {
        m_nfcA = null;
        m_mifare = mifare;
    }

    public TagWriter(NfcA nfcA) {
        m_nfcA = nfcA;
        m_mifare = null;
    }

    public static TagWriter get(Tag tag) {
        MifareUltralight mifare = MifareUltralight.get(tag);
        if (mifare != null)
            return new TagWriter(mifare);
        NfcA nfcA = NfcA.get(tag);
        if (nfcA != null) {
            if (nfcA.getSak() == 0x00 && tag.getId()[0] == NXP_MANUFACTURER_ID)
                return new TagWriter(nfcA);
        }

        return null;
    }
        
    public static void writeToTagRaw(NTAG215 mifare, byte[] tagData, boolean validateNtag) throws Exception {
        validate(mifare, tagData, validateNtag);

        validateBlankTag(mifare);

        try {
            byte[][] pages = TagUtil.splitPages(tagData);
            writePages(mifare, 3, 129, pages);
            TagMo.Debug(TAG, R.string.data_write);
        } catch (Exception e) {
            throw new Exception(TagMo.getStringRes(R.string.data_write_error), e);
        }
        try {
            writePassword(mifare);
            TagMo.Debug(TAG, R.string.password_write);
        } catch (Exception e) {
            throw new Exception(TagMo.getStringRes(R.string.password_write_error), e);
        }
        try {
            writeLockInfo(mifare);
            TagMo.Debug(TAG, R.string.lock_write);
        } catch (Exception e) {
            throw new Exception(TagMo.getStringRes(R.string.lock_write_error), e);
        }
    }

    private static void validateBlankTag(NTAG215 mifare) throws Exception {
        byte[] lockPage = mifare.readPages(0x02);
        TagMo.Debug(TAG, Util.bytesToHex(lockPage));
        if (lockPage[2] == (byte) 0x0F && lockPage[3] == (byte) 0xE0) {
            TagMo.Debug(TAG, R.string.locked);
            throw new Exception(TagMo.getStringRes(R.string.tag_already_written));
        }
        TagMo.Debug(TAG, R.string.unlocked);
    }

    public static void writeToTagAuto(NTAG215 mifare, byte[] tagData, KeyManager keyManager, boolean validateNtag, boolean supportPowerTag) throws Exception {
        byte[] idPages = mifare.readPages(0);
        if (idPages == null || idPages.length != TagUtil.PAGE_SIZE * 4)
            throw new Exception(TagMo.getStringRes(R.string.fail_read_size));

        boolean isPowerTag = false;
        if (supportPowerTag) {
            byte[] sig = mifare.transceive(SIG_CMD);
            isPowerTag = compareRange(sig, POWERTAG_SIGNATURE, 0, POWERTAG_SIGNATURE.length);
        }

        TagMo.Debug(TAG, R.string.power_tag_exists, String.valueOf(isPowerTag));

        tagData = TagUtil.decrypt(keyManager, tagData);
        if (isPowerTag) {
            //use a pre-determined static id for powertag
            tagData = TagUtil.patchUid(POWERTAG_IDPAGES, tagData);
        } else {
            tagData = TagUtil.patchUid(idPages, tagData);
        }
        tagData = TagUtil.encrypt(keyManager, tagData);

        TagMo.Debug(TAG, Util.bytesToHex(tagData));

        if (!isPowerTag) {
            validate(mifare, tagData, validateNtag);
            validateBlankTag(mifare);
        }

        if (isPowerTag) {
            byte[] oldid = mifare.getTag().getId();
            if (oldid == null || oldid.length != 7)
                throw new Exception(TagMo.getStringRes(R.string.fail_read_uid));

            TagMo.Debug(TAG, R.string.old_uid, Util.bytesToHex(oldid));

            byte[] page10 = mifare.readPages(0x10);
            TagMo.Debug(TAG, R.string.page_ten, Util.bytesToHex(page10));

            String page10bytes = Util.bytesToHex(new byte[]{page10[0], page10[3]});

            byte[] ptagKeySuffix = PTagKeyManager.getKey(oldid, page10bytes);
            byte[] ptagKey = Util.hexStringToByteArray(POWERTAG_KEY);
            System.arraycopy(ptagKeySuffix, 0, ptagKey, 8, 8);

            TagMo.Debug(TAG, R.string.ptag_key, Util.bytesToHex(ptagKey));

            mifare.transceive(COMP_WRITE_CMD);
            mifare.transceive(ptagKey);

            if (!(idPages[0] == (byte) 0xFF && idPages[1] == (byte) 0xFF))
                doAuth(mifare);
        }

        byte[][] pages = TagUtil.splitPages(tagData);
        if (isPowerTag) {
            byte[] zeropage = Util.hexStringToByteArray("00000000");
            mifare.writePage(0x86, zeropage); //PACK
            writePages(mifare, 0x01, 0x84, pages);
            mifare.writePage(0x85, zeropage); //PWD
            mifare.writePage(0x00, pages[0]); //UID
            mifare.writePage(0x00, pages[0]); //UID
        } else {
            try {
                writePages(mifare, 3, 129, pages);
                TagMo.Debug(TAG, R.string.data_write);
            } catch (Exception e) {
                throw new Exception(TagMo.getStringRes(R.string.data_write_error), e);
            }
            try {
                writePassword(mifare);
                TagMo.Debug(TAG, R.string.password_write);
            } catch (Exception e) {
                throw new Exception(TagMo.getStringRes(R.string.password_write_error), e);
            }
            try {
                writeLockInfo(mifare);
                TagMo.Debug(TAG, R.string.lock_write);
            } catch (Exception e) {
                throw new Exception(TagMo.getStringRes(R.string.lock_write_error), e);
            }
        }
    }

    public static void restoreTag(NTAG215 mifare, byte[] tagData, boolean ignoreUid, KeyManager keyManager, boolean validateNtag) throws Exception {
        if (!ignoreUid)
            validate(mifare, tagData, validateNtag);
        else {
            byte[] liveData = readFromTag(mifare);
            if (!compareRange(liveData, tagData, 0, 9)) {
                //restoring to different tag: transplant mii and appdata to livedata and re-encrypt

                liveData = TagUtil.decrypt(keyManager, liveData);
                tagData = TagUtil.decrypt(keyManager, tagData);

                System.arraycopy(tagData, 0x08, liveData, 0x08, 0x1B4 - 0x08);

                tagData = TagUtil.encrypt(keyManager, liveData);
            }
        }

        doAuth(mifare);
        byte[][] pages = TagUtil.splitPages(tagData);
        writePages(mifare, 4, 12, pages);
        writePages(mifare, 32, 129, pages);
    }

    static void validate(NTAG215 mifare, byte[] tagData, boolean validateNtag) throws Exception {
        if (tagData == null)
            throw new Exception(TagMo.getStringRes(R.string.no_source_data));

        if (validateNtag) {
            try {
                byte[] versionInfo = mifare.transceive(new byte[]{(byte) 0x60});
                if (versionInfo == null || versionInfo.length != 8)
                    throw new Exception(TagMo.getStringRes(R.string.tag_version_error));
                if (versionInfo[0x02] != (byte) 0x04 || versionInfo[0x06] != (byte) 0x11)
                    throw new Exception(TagMo.getStringRes(R.string.tag_format_error));
            } catch (Exception e) {
                TagMo.Error(TAG, R.string.version_error, e);
                throw e;
            }
        }

        byte[] pages = mifare.readPages(0);
        if (pages == null || pages.length != TagUtil.PAGE_SIZE * 4)
            throw new Exception(TagMo.getStringRes(R.string.fail_read_size));

        if (!compareRange(pages, tagData, 0, 9))
            throw new Exception(TagMo.getStringRes(R.string.fail_mismatch_uid));

        TagMo.Error(TAG, R.string.validation_success);
    }

    static boolean compareRange(byte[] data, byte[] data2, int data2offset, int len) {
        for (int i = data2offset, j = 0; j < len; i++, j++) {
            if (data[j] != data2[i])
                return false;
        }
        return true;
    }

    public static final int BULK_READ_PAGE_COUNT = 4;

    public static byte[] readFromTag(NTAG215 tag) throws Exception {
        byte[] tagData = new byte[TagUtil.TAG_FILE_SIZE];
        int pageCount = TagUtil.TAG_FILE_SIZE / TagUtil.PAGE_SIZE;

        for (int i = 0; i < pageCount; i += BULK_READ_PAGE_COUNT) {
            byte[] pages = tag.readPages(i);
            if (pages == null || pages.length != TagUtil.PAGE_SIZE * BULK_READ_PAGE_COUNT)
                throw new Exception(TagMo.getStringRes(R.string.fail_invalid_size));

            int dstIndex = i * TagUtil.PAGE_SIZE;
            int dstCount = Math.min(BULK_READ_PAGE_COUNT * TagUtil.PAGE_SIZE, tagData.length - dstIndex);

            System.arraycopy(pages, 0, tagData, dstIndex, dstCount);
        }

        TagMo.Debug(TAG, Util.bytesToHex(tagData));
        return tagData;
    }
    
    public static String byteToHex(byte[] bytes){
        String strHex = "";
        StringBuilder sb = new StringBuilder("");
        for (int n = 0; n < bytes.length; n++) {
            strHex = Integer.toHexString(bytes[n] & 0xFF);
            sb.append((strHex.length() == 1) ? "0" + strHex : strHex); // 每个字节由两个字符表示，位数不够，高位补0
        }
        return sb.toString().trim();
    }

    static void writePages(NTAG215 tag, int pagestart, int pageend, byte[][] data) throws Exception {
        byte[] bigdata = new byte[(pageend-pagestart+1)*6];
        for (int i = pagestart,j = 0; i <= pageend; i++,j ++) {
            //tag.writePage(i, data[i]);//old
            m_mifare.writePage(i, data[i]);
            
            TagMo.Debug(TAG, R.string.write_page, String.valueOf(i));
           //writepage-cdoe
            byte[] cdata = data[i];
            byte[] cmd = new byte[cdata.length + 2];
            cmd[0] = (byte) CMD_WRITE;
            cmd[1] = (byte) i;
             //writepage-cdoe
           int ii=j*6;
            System.arraycopy(cdata, 0, cmd, 2, cdata.length);
           System.arraycopy(cmd, 0, bigdata, ii, cmd.length);
        }
        StringBuilder str=new StringBuilder();
        str.append(byteToHex(bigdata));
        int last=str.length();
        for(int iii =last -12;iii>0;iii-=12){
            str.insert(iii,',');
        }
        //throw new Exception(str.toString());     
    }

    static void writePassword(NTAG215 tag) throws IOException {
        byte[] pages0_1 = tag.readPages(0);

        if (pages0_1 == null || pages0_1.length != TagUtil.PAGE_SIZE * 4)
            throw new IOException(TagMo.getStringRes(R.string.read_failed));

        byte[] uid = TagUtil.uidFromPages(pages0_1);
        byte[] password = TagUtil.keygen(uid);

        TagMo.Debug(TAG, R.string.password, Util.bytesToHex(password));

        TagMo.Debug(TAG, R.string.write_pack);
        tag.writePage(0x86, new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0, (byte) 0});

        TagMo.Debug(TAG, R.string.write_pwd);
        tag.writePage(0x85, password);
    }

    static void writeLockInfo(NTAG215 tag) throws IOException {
        byte[] pages = tag.readPages(0);

        if (pages == null || pages.length != TagUtil.PAGE_SIZE * 4)
            throw new IOException(TagMo.getStringRes(R.string.read_failed));

        tag.writePage(2, new byte[]{pages[2 * TagUtil.PAGE_SIZE], pages[(2 * TagUtil.PAGE_SIZE) + 1], (byte) 0x0F, (byte) 0xE0}); //lock bits
        tag.writePage(130, new byte[]{(byte) 0x01, (byte) 0x00, (byte) 0x0F, (byte) 0x00}); //dynamic lock bits. should the last bit be 0xBD accoridng to the nfc docs though: //Remark: Set all bits marked with RFUI to 0, when writing to the dynamic lock bytes.
        tag.writePage(131, new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x04}); //config
        tag.writePage(132, new byte[]{(byte) 0x5F, (byte) 0x00, (byte) 0x00, (byte) 0x00}); //config
    }

    static void doAuth(NTAG215 tag) throws Exception {
        byte[] pages0_1 = tag.readPages(0);

        if (pages0_1 == null || pages0_1.length != TagUtil.PAGE_SIZE * 4)
            throw new Exception(TagMo.getStringRes(R.string.read_failed));

        byte[] uid = TagUtil.uidFromPages(pages0_1);
        byte[] password = TagUtil.keygen(uid);

        TagMo.Debug(TAG, R.string.password, Util.bytesToHex(password));

        byte[] auth = new byte[]{
                (byte) 0x1B,
                password[0],
                password[1],
                password[2],
                password[3]
        };
        byte[] response = tag.transceive(auth);
        if (response == null)
            throw new Exception(TagMo.getStringRes(R.string.auth_null));
        String respStr = Util.bytesToHex(response);
        TagMo.Error(TAG, R.string.auth_response, respStr);
        if (!"8080".equals(respStr)) {
            throw new Exception(TagMo.getStringRes(R.string.auth_failed));
        }
    }

}
