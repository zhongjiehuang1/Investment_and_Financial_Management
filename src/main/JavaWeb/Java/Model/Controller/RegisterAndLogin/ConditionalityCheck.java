package Model.Controller.RegisterAndLogin;

public class ConditionalityCheck {

    public static boolean isEmailAddressValid(java.lang.String emailAddress) {
        int lengthOfEmailAddress = emailAddress.length();
        boolean aExists = isAExists(emailAddress, lengthOfEmailAddress), fieldValid = false;
        if (aExists) {
            int indexOfA = getIndexOfA(emailAddress);
            fieldValid = isFieldValid(emailAddress, indexOfA);
        }
        return aExists && fieldValid;
    }
    private static boolean isAExists(java.lang.String emailAddress, int lengthOfEmailAddress) {
        boolean isAExist = false;
        int numberOfA = 0;
        for (int i = 0; i < lengthOfEmailAddress; i++) {
            if (emailAddress.charAt(i) == '@') {
                isAExist = true;
                numberOfA++;
            }
        }
        return isAExist && numberOfA == 1;
    }
    private static int getIndexOfA(java.lang.String emailAddress) {
        int index = -1;
        boolean needToContinue = true;
        while (needToContinue) {
            index++;
            if (emailAddress.charAt(index) == '@') {
                needToContinue = false;
            }
        }
        return index + 1;
    }
    private static boolean isFieldValid(java.lang.String emailAddress, int index) {
        java.lang.String afterA = emailAddress.substring(index);
        return afterA.equals("outlook.com") || afterA.equals("gmail.com") || afterA.equals("qq.com") ||
                afterA.equals("icloud.com");
    }

    static boolean accountAlreadyExist(java.lang.String emailAddress) {
        return DatabaseService.Model.Controller.RegisterAndLogin.ConditionalityCheck.accountAlreadyExist(emailAddress);
    }

}
