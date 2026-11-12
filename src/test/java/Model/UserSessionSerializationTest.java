package Model;

import Model.UserAssets.InvestmentAsset;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

public class UserSessionSerializationTest {
    private static byte[] serialize(User user)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(user);}return bytes.toByteArray();
    }
    private static User deserialize(byte[] bytes)throws Exception{
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))){return (User)in.readObject();}
    }
    private static User identity(){User user=new User();user.setId(7);user.setGeneration(UUID.randomUUID().toString());user.setForename("Synthetic");user.setSurname("Member");user.setEmailAddress("session@finance.invalid");user.setCurrencyCurrentChosen("USD");return user;}
    @Test public void actualObjectStreamRoundtripCopiesIdentityAndPreferenceButDropsFinancialCaches()throws Exception{
        User user=identity();user.setCurrencies(new String[]{"GBP","USD"});user.setPlatforms(new String[]{"Synthetic platform"});
        InvestmentAsset cached=new InvestmentAsset();cached.setInvestmentAmount("123456.78");user.setInvestmentAssets(new InvestmentAsset[]{cached});
        byte[] encoded=serialize(user);User copy=deserialize(encoded);
        assertNotSame(user,copy);assertEquals(user.getId(),copy.getId());assertEquals(user.getGeneration(),copy.getGeneration());
        assertEquals(user.getForename(),copy.getForename());assertEquals(user.getSurname(),copy.getSurname());assertEquals(user.getEmailAddress(),copy.getEmailAddress());assertEquals("USD",copy.getCurrencyCurrentChosen());
        assertArrayEquals(user.getCurrencies(),copy.getCurrencies());assertNotSame(user.getCurrencies(),copy.getCurrencies());
        assertArrayEquals(user.getPlatforms(),copy.getPlatforms());assertNotSame(user.getPlatforms(),copy.getPlatforms());
        assertNull(copy.getInvestmentAssets());assertNull(copy.getNonInvestmentAssets());assertNull(copy.getBalanceOfIncomesAndExpenditures());
        assertNotNull(copy.getTotalCurrentNetAssets());assertNotNull(copy.getCurrencyDistributionOfCurrentNetAssets());assertNotNull(copy.getRiskDistributionOfCurrentNetAssets());
        assertNotNull(copy.getCurrencyDistributionOfVeryLowAndLowRiskInCurrentNetAssets());assertNotNull(copy.getRangeOfRiskDistributionOfCurrentNetAssets());assertNotNull(copy.getBasicVariablesAndData());
        assertEquals("0.00",copy.getTotalCurrentNetAssets().getTotalCurrentNetAssets());
        String wire=new String(encoded,StandardCharsets.ISO_8859_1);assertFalse(wire.contains("InvestmentAsset"));assertFalse(wire.contains("123456.78"));
        assertEquals(copy.getGeneration(),deserialize(serialize(copy)).getGeneration());
    }
    @Test public void malformedSessionIdentityCannotBeDeserializedAsAnAuthenticatedUser()throws Exception{
        User user=identity();user.setGeneration("invalid");byte[] bytes=serialize(user);assertThrows(InvalidObjectException.class,()->deserialize(bytes));
        user=identity();user.setCurrencyCurrentChosen("not-a-currency");byte[] badCurrency=serialize(user);assertThrows(InvalidObjectException.class,()->deserialize(badCurrency));
    }
}
