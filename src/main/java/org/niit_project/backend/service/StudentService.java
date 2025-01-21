package org.niit_project.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.validation.Valid;
import org.niit_project.backend.dto.ApiResponse;
import org.niit_project.backend.entities.*;
import org.niit_project.backend.repository.AdminRepository;
import org.niit_project.backend.repository.StudentRepository;
import org.niit_project.backend.utils.JwtTokenUtil;
import org.niit_project.backend.utils.PhoneNumberConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;


import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.beans.Expression;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;

@Service
public class StudentService {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private MongoTemplate mongoTemplate;


    @Autowired
    private BCryptPasswordEncoder passwordEncoder;


    Optional<Student> getCompactStudent(String userId){
        return studentRepository.findById(userId);
    }

    Optional<Student> getStudentEmail(String email){
        return studentRepository.findByEmail(email);
    }

    private Counselor getFreeCounselor() throws Exception{

        var matchOperation = Aggregation.match(Criteria.where("role").in("counselor"));
        var addFieldsOperation = Aggregation.addFields().addField("menteesCount").withValueOfExpression("{$size: '$mentees'}").build();

        // Put the more busy counselors on top
        var sortOperation = Aggregation.sort(Sort.Direction.DESC, "menteesCount");

        var aggregation = Aggregation.newAggregation(matchOperation, addFieldsOperation, sortOperation);

        var results = mongoTemplate.aggregate(aggregation, "admins", Counselor.class).getMappedResults();

        if(results.isEmpty()){
            throw new Exception("There are no counselors yet");
        }


        // To get the free-est counselor
        return results.get(results.size()-1);
    }

    public Student registerStudent(@Valid Student student) throws Exception {
        // Null checks
        if(student.getEmail() == null){
            throw new Exception("Email cannot be null");
        }
        if(student.getPhoneNumber() == null){
            throw new Exception("Phone Number cannot be null");
        }
        if(student.getFirstName() == null){
            throw new Exception("First Name cannot be null");
        }
        if(student.getLastName() == null){
           throw new Exception("Last Name cannot be null");
        }
        if(student.getPassword() == null){
           throw new Exception("Password cannot be null");
        }

        // Just to convert the phone number properly
        // To +234 format
        student.setPhoneNumber(PhoneNumberConverter.convertToFull(student.getPhoneNumber()));

        /// To make sure that you're not trying to register with an email
        /// Or phone number that already exists
        var gottenEmail = studentRepository.findByEmail(student.getEmail());
        var gottenPhoneNumber = studentRepository.findByPhoneNumber(student.getPhoneNumber());
        if(gottenEmail.isPresent() || gottenPhoneNumber.isPresent()){
            throw new Exception(gottenEmail.isPresent()? "email already exists":"phone number already exists");
        }



        var freeCounselor = getFreeCounselor();
        student.setPassword(passwordEncoder.encode(student.getPassword()));
        student.setId(null);
        student.setCreatedAt(LocalDateTime.now());
        student.setColor(Colors.getRandomColor());
        // Assign the free-est counselor to this user
        student.setCounselor(freeCounselor.getId());

        final Student savedStudent = studentRepository.save(student);

        // Once the user has been saved with this counselor, we update it on the counselors end as well
        freeCounselor.addMentee(savedStudent.getId());
        mongoTemplate.save(freeCounselor, "admins");


        savedStudent.setToken(generateToken(savedStudent.getId()));

        // We also create a Stream Account for the user
        createStreamUser(savedStudent);

        return savedStudent;
    }

    public boolean isStudent(String studentId){
        return studentRepository.existsById(studentId);
    }

    public Student login(@Valid Student student) throws Exception{
        // Just to convert the phone number properly
        // To +234 format
        if(student.getPhoneNumber() != null){
            student.setPhoneNumber(PhoneNumberConverter.convertToFull(student.getPhoneNumber()));
        }

        final boolean isEmailLogin = student.getEmail() != null;

        if(!isEmailLogin && student.getPhoneNumber() == null){
            throw new Exception("Either Phone Number Or Email must be used");
        }

        Optional<Student> gottenStudent = isEmailLogin? studentRepository.findByEmail(student.getEmail()): studentRepository.findByPhoneNumber(student.getPhoneNumber());

        if(gottenStudent.isEmpty()){
            throw new Exception("Student not found");
        }

        if(!passwordEncoder.matches(student.getPassword(), gottenStudent.get().getPassword())){
            throw new Exception("Wrong password");
        }

        var loggedInStudent = gottenStudent.get();
        var token = generateToken(loggedInStudent.getId());
        loggedInStudent.setToken(token);

        // We generate the token return it
        return loggedInStudent;
    }

    public String generateToken(String userId) throws Exception{

        // We check if the student exists before we create such token
        var userExists = studentRepository.existsById(userId);
        if(!userExists){
            throw new Exception("Student Doesn't exist");
        }


        var env = Dotenv.load();
        var tokenUtil = new JwtTokenUtil();
        tokenUtil.setSecretKey(env.get("STREAM_API_SECRET"));
        return tokenUtil.generateToken(userId);


//        try {
////            String token = Jwts.builder()
////                    .setSubject(userId)
////                    .setIssuedAt(new Date())
////                    .setExpiration(new Date(System.currentTimeMillis() + (24L * 60 * 60 * 1_000_000))) // 1000 day validity
////                    .signWith(SignatureAlgorithm.HS256, env.get("STREAM_API_SECRET").getBytes())
////                    .compact();


//          Option 2
//            Map<String, String> payload = new HashMap<>();
//            payload.put("user_id", userId);
//            ObjectMapper objectMapper = new ObjectMapper();
//            String jsonPayload = objectMapper.writeValueAsString(payload);
//
//            HttpRequest request = HttpRequest.newBuilder()
//                    .uri(URI.create("video.stream-io-api.com/api/v2/users?api_key=" + env.get("STREAM_API_KEY")))
//                    .header("Content-Type", "application/json")
//                    .header("")
//                    .header("Authorization", "Bearer " + )
//                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
//                    .build();
//
//            // Send request
//            HttpClient client = HttpClient.newHttpClient();
//            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
//            System.out.println(response.body());
//
//            var token = new ObjectMapper().readTree(response.body()).get("token").asText();
//            return token;
//        } catch (Exception e) {
//            throw new Exception(e.getMessage());
//        }
    }

    public void createStreamUser(Student student) throws Exception{
        var env = Dotenv.load();
        var streamUser = new StreamUser();
        streamUser.setId(student.getId());
        streamUser.setName(student.getFullName());
        streamUser.setColor(student.getColor());


        var payload = new HashMap<String, Object>();
        var userPayload = new HashMap<String, Object>();
        userPayload.put(streamUser.getId(), streamUser);
        payload.put("users", userPayload);

        var payloadString = new ObjectMapper().writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://video.stream-io-api.com/api/v2/users?api_key=" + env.get("STREAM_API_KEY")))
                .header("Content-Type", "application/json")
                .header("stream-auth-type", "jwt")
                .header("Authorization", env.get("STREAM_API_TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(payloadString))
                .build();

        // Send request
        HttpClient client = HttpClient.newHttpClient();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println(response.body());


    }
}
